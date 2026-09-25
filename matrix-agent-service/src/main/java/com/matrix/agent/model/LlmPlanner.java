package com.matrix.agent.model;

import com.matrix.agent.contract.ModelConfig;

import com.matrix.agent.contract.LlmClient;

import android.util.Log;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryKeyCatalog;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.ToolDefinition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 旧版兼容路径——结构化 JSON 单轮规划(新版本起 LlmModelGateway 优先
 * 走 NATIVE_TOOL_CALLING 直连路径,LlmPlanner 仅作 STRUCTURED_JSON_COMPATIBILITY fallback)。
 *
 * <p>兼容协议也直接产出 {@link ModelTurn}，与原生 Tool Calling 共用 Agent Loop 的唯一
 * 决策模型；不再经过已废弃的批量计划过渡对象。
 */
public final class LlmPlanner {
    private static final String TAG = "MatrixAgent";
    private static final String PROMPT_PREFIX =
            "你是车机助手。只输出一个 JSON 对象，不要 Markdown。"
            + "格式：{\"summary\":\"...\",\"steps\":[{\"capability\":\"...\",\"arguments\":{}}]}。"
            + "如果用户的请求是聊天、询问身份、问候等不需要执行操作的对话，"
            + "直接在 summary 中用自然语言回答，steps 返回空数组 []。"
            + "只有需要实际控制设备或查询数据时才调用能力。"
            + "只能使用下面注册的能力，不允许创造能力名。最多8步。\n";

    private final LlmClient client;
    private final ModelConfig config;
    private final MemoryStore memoryStore;
    /**
     * 可选 Memory 召回——null 时维持旧版行为(289 测试兼容)。
     * 注入后,savedKeysFor 附加 working/episodic/semantic snippet.key 到 prompt。
     */
    private MemoryRecaller memoryRecaller;

    public LlmPlanner(LlmClient client, ModelConfig config) {
        this(client, config, null);
    }

    /**
     * 接受 {@link LlmClient} 接口的测试用构造器。
     *
     * <p>生产路径继续走 {@link ModelApiClient} 构造器(签名不变,向后兼容旧版)。
     * 测试用此构造器注入 fake LlmClient,无需起 HttpServer 验证 prompt 装配。
     */
    public LlmPlanner(LlmClient client, ModelConfig config, MemoryStore memoryStore) {
        this.client = client;
        this.config = config;
        this.memoryStore = memoryStore;
    }

    /** 可选注入 Memory 召回器。 */
    public void setMemoryRecaller(MemoryRecaller recaller) {
        this.memoryRecaller = recaller;
    }

    public ModelTurn decide(AgentRequest request, SessionContext context, List<ToolDefinition> tools) {
        try {
            // The caller supplies the same per-zone projection used for the wire schema.
            String systemPrompt = PROMPT_PREFIX + plannerInstructions(tools);
            String userPrompt = "发起者=" + request.getActor()
                    + "\n最近上下文=" + context.getRecentTurns()
                    + "\n已保存的偏好 key 列表=" + savedKeysFor(request)
                    + "\n用户请求=" + request.getText();
            String raw = client.complete(config, systemPrompt, userPrompt);
            JSONObject root = new JSONObject(cleanJson(raw));
            JSONArray array = root.getJSONArray("steps");
            List<ToolCall> steps = new ArrayList<>();
            for (int i = 0; i < array.length() && i < 8; i++) {
                JSONObject step = array.getJSONObject(i);
                steps.add(new ToolCall(step.getString("capability"),
                        toMap(step.optJSONObject("arguments"))));
            }
            String summary = "LLM/" + config.displayName + "："
                    + root.optString("summary", "结构化规划");
            return steps.isEmpty() ? ModelTurn.directAnswer(summary)
                    : ModelTurn.ofToolCalls(steps, summary);
        } catch (Exception error) {
            throw new IllegalStateException("模型规划失败：" + safeMessage(error), error);
        }
    }

    /** Builds the legacy prompt fragment from an already policy-projected tool list. */
    private static String plannerInstructions(List<ToolDefinition> tools) {
        StringBuilder text = new StringBuilder();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
                text.append("- ").append(tool.getCapabilityName());
                if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
                    text.append(": ").append(tool.getDescription());
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        if (object == null) return map;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            map.put(key, value == JSONObject.NULL ? null : value);
        }
        return map;
    }

    /** Uses the canonical actor-to-user projection; planner and capability execution must agree. */
    private static String userId(AgentRequest request) {
        return ActorUsers.userIdOf(request);
    }

    /**
     * 列出该用户已保存的所有偏好 key(只 key,不含 value——避免敏感数据进 prompt)。
     * 注入到 prompt 后,模型查询 memory.preference.get 时直接用现有 key,不再脑补命名。
     *
     * <p>memoryRecaller != null 时,附加 working/episodic/semantic 层 snippet.key
     * 到末尾(不附加 PREFERENCE——已通过 MemoryStore.getAllPreferences 取)。
     */
    private String savedKeysFor(AgentRequest request) {
        String userId = userId(request);
        MemoryScope scope = new MemoryScope(userId, request.getOccupantZone());
        String preferenceBlock = preferenceKeysFor(scope);
        if (memoryRecaller == null) return preferenceBlock;
        try {
            List<MemorySnippet> recalled = memoryRecaller.recall(scope, request.getSessionId(), request.getText(), 8);
            if (recalled == null || recalled.isEmpty()) return preferenceBlock;
            List<MemorySnippet> extra = recalled.stream()
                    .filter(snippet -> snippet.getLayer() != MemoryLayer.PREFERENCE).toList();
            if (extra.isEmpty()) return preferenceBlock;
            return preferenceBlock + "\n其他已召回的 Memory:"
                    + com.matrix.agent.task.prompt.DefaultPromptBuilder.formatRecalledMemory(extra);
        } catch (Exception error) {
            Log.w(TAG, "[LlmPlanner] memoryRecaller lookup failed: " + error.getMessage());
            return preferenceBlock;
        }
    }

    /** 抽出旧版偏好 key 列表逻辑,保持向后兼容。 */
    private String preferenceKeysFor(MemoryScope scope) {
        if (memoryStore == null) return "（MemoryStore 未注入,无历史 key）";
        try {
            Map<String, String> all = memoryStore.getAllPreferences(scope);
            if (all == null || all.isEmpty()) {
            Log.d(TAG, "[LlmPlanner] savedKeys scope=" + scope + " -> empty");
                return "（无）";
            }
            Set<String> sorted = new TreeSet<>();
            for (String key : all.keySet()) {
                String projected = MemoryKeyCatalog.promptKey(MemoryLayer.PREFERENCE, key);
                if (projected != null) sorted.add(projected);
            }
            StringBuilder joinedBuilder = new StringBuilder();
            int count = 0;
            for (String key : sorted) {
                if (count >= 8 || joinedBuilder.length() + key.length() > 512) break;
                if (count++ > 0) joinedBuilder.append(", ");
                joinedBuilder.append(key);
            }
            String joined = joinedBuilder.toString();
            Log.d(TAG, "[LlmPlanner] savedKeys scope=" + scope
                    + " count=" + sorted.size());
            return "查询时必须使用这些精确字符串之一:[" + joined + "]";
        } catch (Exception error) {
            Log.w(TAG, "[LlmPlanner] savedKeys lookup failed: " + error.getMessage());
            return "（读取失败）";
        }
    }

    private static String cleanJson(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                value = value.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }
}
