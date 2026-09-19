package com.matrix.agent.demo;

import android.util.Log;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.ToolCall.ArgumentProvenance;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.session.SessionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线模式 ModelGateway:用关键词匹配模拟单步决策,不依赖云端模型。
 *
 * 工作方式:每次 decide 时基于 user prompt 生成稳定 candidates 列表,
 * 按 conversation 中已存在 assistant tool_call 数定位下一步,跑完返回 STOP。
 *
 * "副驾也一样" 这类跨任务上下文用 {@link SessionContext#getLastTemperature()} 取最近温度,
 * 默认 24℃(null 时)。这样既保留跨任务上下文,又保证 candidates 列表结构稳定。
 */
public final class DemoModelGateway implements ModelGateway {
    private static final String TAG = "MatrixAgent";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d{1,3})");

    @Override
    public ModelTurn decide(ModelTurnRequest request) {
        AgentRequest agentRequest = request.getAgentRequest();
        Log.d(TAG, "[Demo] decide req=" + agentRequest.getRequestId()
                + " text=\"" + truncate(agentRequest.getText(), 60) + "\"");

        int step = countAssistantToolCalls(request.getConversation());
        List<ToolCall> candidates = generateCandidates(request);
        Log.d(TAG, "[Demo] candidates=" + candidates.size() + " currentStep=" + step);
        if (candidates.isEmpty()) {
            Log.i(TAG, "[Demo] no candidate matched -> direct answer");
            return ModelTurn.directAnswer("Demo: 没有识别到可执行步骤");
        }
        if (step >= candidates.size()) {
            Log.i(TAG, "[Demo] step overflow -> direct answer (任务完成)");
            return ModelTurn.directAnswer("任务完成");
        }
        ToolCall next = candidates.get(step);
        Log.i(TAG, "[Demo] emit step " + (step + 1) + "/" + candidates.size()
                + " cap=" + next.getCapabilityName() + " args=" + next.getArguments());
        return ModelTurn.ofToolCalls(java.util.Collections.singletonList(next),
                "Demo: 第 " + (step + 1) + "/" + candidates.size() + " 步");
    }

    private static int countAssistantToolCalls(List<AgentMessage> conversation) {
        int count = 0;
        for (AgentMessage msg : conversation) {
            if (msg.getRole() == AgentMessage.Role.ASSISTANT && !msg.getToolCalls().isEmpty()) {
                count += msg.getToolCalls().size();
            }
        }
        return count;
    }

    private List<ToolCall> generateCandidates(ModelTurnRequest request) {
        AgentRequest agentRequest = request.getAgentRequest();
        String text = agentRequest.getText();
        Actor actor = agentRequest.getActor();
        SessionContext ctx = request.getSessionContext();
        Integer lastTemp = ctx.getLastTemperature();
        int defaultTemp = lastTemp == null ? 24 : lastTemp;

        List<ToolCall> steps = new ArrayList<>();

        if (containsAny(text, "ADAS", "adas", "辅助驾驶")) {
            steps.add(call("vehicle.adas.set_enabled", "enabled", true));
        }

        if (text.contains("记住") && containsAny(text, "温度", "度")) {
            Integer temperature = findNumber(text);
            if (temperature != null) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("key", "preferred_temperature");
                args.put("value", String.valueOf(temperature));
                steps.add(new ToolCall("memory.preference.save", args));
            }
        }

        if (containsAny(text, "喜欢多少度", "常用温度", "温度偏好")) {
            steps.add(call("memory.preference.get", "key", "preferred_temperature"));
        }

        boolean sameForPassenger = text.contains("副驾也一样") || text.contains("副驾驶也一样");
        if (sameForPassenger) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("zone", "passenger");
            if (lastTemp != null) {
                args.put("temperature", lastTemp);
            }
            // lastTemp 为空时不补默认值,让 Policy 以"缺少 temperature"拒绝,
            // 这样可暴露出"会话间不共享上下文"的语义(见 passengerAndDriverDoNotShareSessionContext)。
            // zone=passenger 是 Demo 推断(用户说"副驾也一样"明确指副驾),标 USER_EXPLICIT。
            steps.add(climateCall("passenger", lastTemp, ArgumentProvenance.USER_EXPLICIT));
        } else if (containsAny(text, "空调", "温度") && !text.contains("偏好") && !containsAny(text, "喜欢多少度")) {
            Integer temperature = findNumber(text);
            if (temperature == null && containsAny(text, "调高一点", "高一点")) {
                temperature = defaultTemp + 2;
            }
            ZoneResolution zone = resolveZone(text, actor);
            steps.add(climateCall(zone.zone, temperature, zone.provenance));
        }

        if (containsAny(text, "座椅加热", "座椅温度")) {
            Integer level = findNumber(text);
            ZoneResolution zone = resolveZone(text, actor);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("zone", zone.zone);
            args.put("level", level == null ? 2 : level);
            Map<String, ArgumentProvenance> provenance = new LinkedHashMap<>();
            provenance.put("zone", zone.provenance);
            steps.add(new ToolCall("vehicle.seat.set_heating_level", args, provenance));
        }

        if (text.contains("电量")) {
            steps.add(new ToolCall("vehicle.info.get_battery", new LinkedHashMap<>()));
        }

        if (text.contains("胎压")) {
            steps.add(new ToolCall("vehicle.info.get_tire_pressure", new LinkedHashMap<>()));
        }

        if (containsAny(text, "音量", "声音")) {
            Integer percent = findNumber(text);
            if (percent != null) steps.add(call("system.media.set_volume", "percent", percent));
        }

        if (containsAny(text, "亮度", "屏幕亮")) {
            Integer percent = findNumber(text);
            if (percent != null) steps.add(call("system.display.set_brightness", "percent", percent));
        }

        if (containsAny(text, "导航", "带我去")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("destination", resolveDestination(text));
            steps.add(new ToolCall("navigation.start_route", args));
        }

        if (steps.isEmpty()) {
            steps.add(call("knowledge.answer", "question", text));
        }

        return steps;
    }

    private static ToolCall call(String capability, String key, Object value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(key, value);
        return new ToolCall(capability, args);
    }

    /** 构造 climate.set_temperature,zone 来源由 caller 决定。 */
    private static ToolCall climateCall(String zone, Integer temperature,
            ArgumentProvenance zoneProvenance) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", zone);
        if (temperature != null) {
            args.put("temperature", temperature);
        }
        Map<String, ArgumentProvenance> provenance = new LinkedHashMap<>();
        provenance.put("zone", zoneProvenance);
        return new ToolCall("vehicle.climate.set_temperature", args, provenance);
    }

    /** zone 来源:用户原话明确指"主驾/副驾" → USER_EXPLICIT;否则按 actor 默认 → MODEL_INFERRED。 */
    private static ZoneResolution resolveZone(String text, Actor actor) {
        if (containsAny(text, "副驾", "副驾驶")) {
            return new ZoneResolution("passenger", ArgumentProvenance.USER_EXPLICIT);
        }
        if (containsAny(text, "主驾", "驾驶位")) {
            return new ZoneResolution("driver", ArgumentProvenance.USER_EXPLICIT);
        }
        return new ZoneResolution(actor == Actor.DRIVER ? "driver" : "passenger",
                ArgumentProvenance.MODEL_INFERRED);
    }

    private static final class ZoneResolution {
        final String zone;
        final ArgumentProvenance provenance;

        ZoneResolution(String zone, ArgumentProvenance provenance) {
            this.zone = zone;
            this.provenance = provenance;
        }
    }

    private static Integer findNumber(String text) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static String resolveDestination(String text) {
        if (containsAny(text, "回家", "回 家")) return "家";
        if (text.contains("公司")) return "公司";
        int index = text.indexOf("导航到");
        if (index >= 0) return cleanDestination(text.substring(index + 3));
        index = text.indexOf("导航去");
        if (index >= 0) return cleanDestination(text.substring(index + 3));
        return "未指定目的地";
    }

    private static String cleanDestination(String value) {
        return value.replace("。", "").replace("，", "").trim();
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
