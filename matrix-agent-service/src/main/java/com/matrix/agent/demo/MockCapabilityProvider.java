package com.matrix.agent.demo;
import com.matrix.agent.task.redact.*;

import com.matrix.agent.intent.MemoryIntentDetector;

import com.matrix.agent.identity.VehicleZone;

import com.matrix.agent.identity.AgentRequest;

import android.util.Log;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.redact.SafeLog;
import com.matrix.agent.data.memory.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;
import com.matrix.agent.task.capability.*;
import com.matrix.agent.task.tool.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stateful AAOS replacement used by the emulator build.
 *
 * <p>重写:删除旧版的 7 个 if-else 硬编码分支,改走
 * {@code Map<String, CapabilityHandler>} 路由表——每个 capability 一个 handler 实现,
 * handler 内部用 {@link VerifyStrategy} 抽象 verify 行为。
 */
public final class MockCapabilityProvider implements CapabilityProvider {
    private static final String TAG = "MatrixAgent";
    private final MemoryStore memoryStore;
    private final MemoryWriter memoryWriter;
    private final Map<String, Object> commandedState = new ConcurrentHashMap<>();
    private final Map<String, Object> observedState = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextVehicleReadback = new AtomicBoolean();
    private final Map<String, CapabilityHandler> handlers = new HashMap<>();

    public MockCapabilityProvider(MemoryStore memoryStore) {
        this(memoryStore, MemoryWriter.NOOP);
    }

    /**
     * 带 {@link MemoryWriter} 的重载,memory.semantic.* handler 走真实持久化路径。
     *
     * <p>旧单参构造器 delegate 到本方法,memoryWriter 默认 {@link MemoryWriter#NOOP}——
     * 所有测试 fake 调用点不需改,保 654 测试零回归。
     */
    public MockCapabilityProvider(MemoryStore memoryStore, MemoryWriter memoryWriter) {
        this.memoryStore = memoryStore;
        this.memoryWriter = memoryWriter == null ? MemoryWriter.NOOP : memoryWriter;
        observedState.put("driver.temperature", 22);
        observedState.put("passenger.temperature", 22);
        observedState.put("driver.seatHeating", 0);
        observedState.put("passenger.seatHeating", 0);
        observedState.put("battery.percent", 78);
        observedState.put("tire.frontLeft.kPa", 240);
        observedState.put("tire.frontRight.kPa", 241);
        observedState.put("tire.rearLeft.kPa", 238);
        observedState.put("tire.rearRight.kPa", 239);
        observedState.put("navigation.destination", "无");
        registerHandlers();
        Log.i(TAG, "[Provider] init mock state observedKeys=" + observedState.size()
                + " handlers=" + handlers.size()
                + " memoryWriter=" + this.memoryWriter.getClass().getSimpleName());
    }

    private void registerHandlers() {
        handlers.put("vehicle.climate.set_temperature", new ClimateSetTemperatureHandler());
        handlers.put("vehicle.seat.set_heating_level", new SeatSetHeatingLevelHandler());
        handlers.put("vehicle.info.get_battery", new InfoGetBatteryHandler());
        handlers.put("vehicle.info.get_tire_pressure", new InfoGetTirePressureHandler());
        handlers.put("navigation.start_route", new NavigationStartRouteHandler());
        handlers.put("memory.preference.save", new MemoryPreferenceSaveHandler());
        handlers.put("memory.preference.get", new MemoryPreferenceGetHandler());
        handlers.put("memory.semantic.save", new MemorySemanticSaveHandler());
        handlers.put("memory.semantic.get", new MemorySemanticGetHandler());
        handlers.put("knowledge.answer", new KnowledgeAnswerHandler());
    }

    @Override
    public ToolResult execute(AgentRequest request, ToolCall call) {
        long started = System.nanoTime();
        String capability = call.getCapabilityName();
        CapabilityHandler handler = handlers.get(capability);
        if (handler == null) {
            Log.w(TAG, "[Provider] unsupported capability=" + capability);
            return new ToolResult(
                    ToolResult.Status.EXECUTION_FAILED,
                    capability,
                    "Mock Provider 不支持该 Capability",
                    Collections.emptyMap(),
                    false,
                    elapsedMillis(started));
        }
        Log.d(TAG, "[Provider] execute cap=" + capability
                + " args=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                + " argKeys=" + call.getArguments().keySet());
        ProviderContext ctx = new ProviderContext(request, call, observedState, commandedState,
                memoryStore, memoryWriter, failNextVehicleReadback);
        return handler.execute(ctx);
    }

    public Map<String, Object> snapshotVehicleState() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(observedState));
    }

    /** Test hook: the next vehicle command is accepted but not reflected by readback. */
    public void failNextVehicleReadback() {
        Log.i(TAG, "[Provider] failNextVehicleReadback ARMED");
        failNextVehicleReadback.set(true);
    }

    // ============ Handler implementations ============

    private static ToolResult result(String capability, String message,
            Map<String, Object> observed, boolean verified, long started) {
        return new ToolResult(
                verified ? ToolResult.Status.SUCCESS : ToolResult.Status.VERIFICATION_FAILED,
                capability,
                message,
                observed,
                verified,
                elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static final class ClimateSetTemperatureHandler implements CapabilityHandler {
        private final VerifyStrategy verifyStrategy = new ReadbackFieldStrategy(
                call -> {
                    VehicleZone zone = VehicleZone.parse(call.argument("zone"));
                    return zone.wireValue() + ".temperature";
                },
                call -> ((Number) call.argument("temperature")).intValue());

        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            VehicleZone zone = VehicleZone.parse(ctx.getCall().argument("zone"));
            int temperature = ((Number) ctx.getCall().argument("temperature")).intValue();
            String key = zone.wireValue() + ".temperature";
            ctx.commandAndApply(key, temperature);
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put(key, ctx.getObservedState().get(key));
            boolean verified = verifyStrategy.verify(ctx);
            Log.d(TAG, "[Provider] climate key=" + key + " value=" + temperature
                    + " readback=" + ctx.getObservedState().get(key) + " verified=" + verified);
            return result("vehicle.climate.set_temperature", "空调温度已设置", readback, verified, started);
        }
    }

    private static final class SeatSetHeatingLevelHandler implements CapabilityHandler {
        private final VerifyStrategy verifyStrategy = new ReadbackFieldStrategy(
                call -> {
                    VehicleZone zone = VehicleZone.parse(call.argument("zone"));
                    return zone.wireValue() + ".seatHeating";
                },
                call -> ((Number) call.argument("level")).intValue());

        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            VehicleZone zone = VehicleZone.parse(ctx.getCall().argument("zone"));
            int level = ((Number) ctx.getCall().argument("level")).intValue();
            String key = zone.wireValue() + ".seatHeating";
            ctx.commandAndApply(key, level);
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put(key, ctx.getObservedState().get(key));
            boolean verified = verifyStrategy.verify(ctx);
            Log.d(TAG, "[Provider] seat key=" + key + " value=" + level + " verified=" + verified);
            return result("vehicle.seat.set_heating_level", "座椅加热等级已设置", readback, verified, started);
        }
    }

    private static final class InfoGetBatteryHandler implements CapabilityHandler {
        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put("battery.percent", ctx.getObservedState().get("battery.percent"));
            return result("vehicle.info.get_battery", "当前电量 78%", readback, true, started);
        }
    }

    private static final class InfoGetTirePressureHandler implements CapabilityHandler {
        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            Map<String, Object> readback = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ctx.getObservedState().entrySet()) {
                if (entry.getKey().startsWith("tire.")) {
                    readback.put(entry.getKey(), entry.getValue());
                }
            }
            return result("vehicle.info.get_tire_pressure", "四轮胎压状态正常", readback, true, started);
        }
    }

    private static final class NavigationStartRouteHandler implements CapabilityHandler {
        private final VerifyStrategy verifyStrategy = new ReadbackFieldStrategy(
                call -> "navigation.destination",
                call -> String.valueOf(call.argument("destination")));

        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            String destination = String.valueOf(ctx.getCall().argument("destination"));
            if ("失败测试点".equals(destination)) {
                Log.w(TAG, "[Provider] navigation -> EXECUTION_FAILED (mock 失败测试点)");
                return new ToolResult(
                        ToolResult.Status.EXECUTION_FAILED,
                        "navigation.start_route",
                        "Mock 导航服务不可用",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            ctx.commandAndApply("navigation.destination", destination);
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put("navigation.destination", ctx.getObservedState().get("navigation.destination"));
            boolean verified = verifyStrategy.verify(ctx);
            Log.d(TAG, "[Provider] navigation dest=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " verified=" + verified);
            return result("navigation.start_route",
                    "已开始导航到“" + destination + "”", readback, verified, started);
        }
    }

    private static final class MemoryPreferenceSaveHandler implements CapabilityHandler {
        private final VerifyStrategy verifyStrategy = new ReadbackGetStrategy("key");

        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            String key = String.valueOf(ctx.getCall().argument("key"));
            String value = String.valueOf(ctx.getCall().argument("value"));
            // 带 epoch 校验的 putPreference——clearUserData 自增 epoch 后,
            // 旧 epoch 的 Provider 写入会被 MemoryStore 拒绝 (return false),杜绝陈旧写回。
            long requestEpoch = ctx.getRequest().getEpoch();
            boolean accepted = ctx.getMemoryStore().putPreferenceChecked(
                    ctx.memoryScope(), key, value, requestEpoch);
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put(key, ctx.getMemoryStore().getPreference(ctx.memoryScope(), key));
            boolean verified = accepted && verifyStrategy.verify(ctx);
            Log.d(TAG, "[Provider] memory.save user=" + ctx.userId()
                    + " key=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " value=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " epoch=" + requestEpoch
                    + " accepted=" + accepted);
            if (!accepted) {
                return result("memory.preference.save",
                        "memory preference rejected: stale epoch (clearUserData occurred)",
                        readback, false, started);
            }
            return result("memory.preference.save",
                    "已记住你的温度偏好：" + value + "℃", readback, verified, started);
        }
    }

    private static final class MemoryPreferenceGetHandler implements CapabilityHandler {
        private final VerifyStrategy verifyStrategy = new ReadbackGetStrategy("key");

        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            String key = String.valueOf(ctx.getCall().argument("key"));
            String value = ctx.getMemoryStore().getPreference(ctx.memoryScope(), key);
            if (value == null) {
                Log.d(TAG, "[Provider] memory.get user=" + ctx.userId()
                        + " key=" + SafeLog.TOOL_ARGS_PLACEHOLDER + " -> not found");
                return new ToolResult(
                        ToolResult.Status.EXECUTION_FAILED,
                        "memory.preference.get",
                        "还没有保存温度偏好",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put(key, value);
            boolean verified = verifyStrategy.verify(ctx);
            Log.d(TAG, "[Provider] memory.get user=" + ctx.userId()
                    + " key=" + key + " -> found");
            return result("memory.preference.get",
                    "你保存的温度偏好是 " + value + "℃", readback, verified, started);
        }
    }

    /**
     * Semantic 显式写入——用户明确要求长期记住的事实/知识(如"我对花生过敏")。
     *
     * <p>现在加 epoch gate——与 preference.save 同模式。requestEpoch
     * 由 AgentRequest.getEpoch() 透传,RoomMemoryWriter 在 Room 事务内做"读 system epoch +
     * 比较 + upsert"原子序列。clearUserData 后,旧 epoch 的 semantic save 也会被拒。
     *
     * <p>用户硬约束——"长期记忆仅由用户决定"。Repository.execute 入口
     * 用 MemoryIntentDetector 判定 {@link AgentRequest#isMemorySaveAllowed()},false 时
     * 本 handler 直接 POLICY_REJECTED,模型重试无效——硬 gate,prompt 约定的双重防线。
     * 杜绝"查天气"等纯查询请求里模型自由调 save 污染长期记忆。
     *
     * <p>主 gate 已上移到 {@code PolicyEngine.checkMemorySaveExplicitGate}
     * (capability-level CAPABILITY_REJECTED,先于 schema 校验,deny → Provider 永不被调)。
     * 本 handler 内的同款 POLICY_REJECTED 检查保留作 defence-in-depth——若未来 Provider
     * 不经过 PolicyEngine 直接调 handler(测试桩 / 第三方 runtime),这层仍守住。
     *
     * <p>fail-log:writeSemantic 返回 false 仅转 EXECUTION_FAILED,不阻塞主路径。
     */
    private static final class MemorySemanticSaveHandler implements CapabilityHandler {
        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            // 硬 gate——用户未显式要求长期记忆时直接 POLICY_REJECTED。
            // 模型看到此状态码后不应再重试同一 capability(与 CAPABILITY_REJECTED 语义一致)。
            if (!ctx.getRequest().isMemorySaveAllowed()) {
                Log.w(TAG, "[Provider] memory.semantic.save POLICY_REJECTED user=" + ctx.userId()
                        + " reason=explicit_memory_intent_not_detected"
                        + " requestEpoch=" + ctx.getRequest().getEpoch());
                return new ToolResult(
                        ToolResult.Status.POLICY_REJECTED,
                        "memory.semantic.save",
                        "长期记忆仅由用户决定——请在请求中显式表达(如\"记住X\"/\"保存X\"/\"别忘了X\")",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            String key = String.valueOf(ctx.getCall().argument("key"));
            String value = String.valueOf(ctx.getCall().argument("value"));
            String zone = ctx.getRequest().getOccupantZone() == null
                    ? "" : ctx.getRequest().getOccupantZone().name();
            String sessionId = ctx.getRequest().getSessionId();
            long requestEpoch = ctx.getRequest().getEpoch();
            boolean accepted = ctx.getMemoryWriter().writeSemantic(
                    ctx.userId(), zone, key, value, 1.0, sessionId, requestEpoch);
            Log.d(TAG, "[Provider] memory.semantic.save user=" + ctx.userId()
                    + " key=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " value=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                    + " epoch=" + requestEpoch
                    + " accepted=" + accepted);
            if (!accepted) {
                return new ToolResult(
                        ToolResult.Status.EXECUTION_FAILED,
                        "memory.semantic.save",
                        "semantic memory write failed (database unavailable / dao error / stale epoch)",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put(key, "<memory>");
            return result("memory.semantic.save",
                    "已记住：" + key, readback, true, started);
        }
    }

    /**
     * Semantic 显式读取——memory.semantic.get capability handler。
     *
     * <p>readSemantic 返回 null(无记录 / 失败)时转 EXECUTION_FAILED,
     * 与 MemoryPreferenceGetHandler 的 not-found 路径一致。
     */
    private static final class MemorySemanticGetHandler implements CapabilityHandler {
        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            String key = String.valueOf(ctx.getCall().argument("key"));
            String zone = ctx.getRequest().getOccupantZone() == null
                    ? "" : ctx.getRequest().getOccupantZone().name();
            String value = ctx.getMemoryWriter().readSemantic(ctx.userId(), zone, key);
            if (value == null) {
                Log.d(TAG, "[Provider] memory.semantic.get user=" + ctx.userId()
                        + " key=" + SafeLog.TOOL_ARGS_PLACEHOLDER + " -> not found");
                return new ToolResult(
                        ToolResult.Status.EXECUTION_FAILED,
                        "memory.semantic.get",
                        "还没有保存这条记忆",
                        Collections.emptyMap(),
                        false,
                        elapsedMillis(started));
            }
            Log.d(TAG, "[Provider] memory.semantic.get user=" + ctx.userId()
                    + " key=" + SafeLog.TOOL_ARGS_PLACEHOLDER + " -> found");
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put(key, "<memory>");
            return result("memory.semantic.get",
                    "已找到这条记忆", readback, true, started);
        }
    }

    private static final class KnowledgeAnswerHandler implements CapabilityHandler {
        @Override
        public ToolResult execute(ProviderContext ctx) {
            long started = System.nanoTime();
            String question = String.valueOf(ctx.getCall().argument("question"));
            Map<String, Object> readback = new LinkedHashMap<>();
            readback.put("answerSource", "mock-offline-knowledge");
            return result("knowledge.answer",
                    "这是离线问答占位结果。已收到问题：\"" + question + "\"。后续由 Model Gateway 接入真实模型。",
                    readback, true, started);
        }
    }
}
