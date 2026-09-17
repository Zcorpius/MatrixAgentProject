package com.matrix.agent.task.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 单次工具调用:capability 名 + 参数 + 参数来源。
 *
 * <p>参数来源({@link ArgumentProvenance})区分参数是怎么进来的——用户原话明确指定、
 * 模型推断、系统默认、上下文补全。Policy 据此判断"参数错可不可以让模型换值重试":
 * <ul>
 *   <li>{@link ArgumentProvenance#USER_EXPLICIT}:用户明确意图,Policy 不能擅自改值
 *       重试(例:副驾说"把主驾温度调到24度" → 直接 CAPABILITY 拒绝,不能改 zone=passenger)。</li>
 *   <li>{@link ArgumentProvenance#MODEL_INFERRED}:模型脑补,可以让模型重新推断</li>
 *       (例:用户说"副驾调温"模型默认填了 zone=driver → PARAMETER 拒绝,提示改 zone=passenger)。</li>
 *   <li>{@link ArgumentProvenance#SYSTEM_DEFAULT} / {@link ArgumentProvenance#CONTEXT_FILLED}:
 *       默认值或上下文补全,与 MODEL_INFERRED 同等处理。</li>
 * </ul>
 *
 * <p>未显式标注来源的参数默认 {@link ArgumentProvenance#MODEL_INFERRED}(向后兼容:
 * 模型 adapter 返回的 ToolCall 都走默认)。
 */
public final class ToolCall {
    public enum ArgumentProvenance {
        USER_EXPLICIT,    // 用户原话明确指定
        MODEL_INFERRED,   // 模型推断/脑补
        SYSTEM_DEFAULT,   // 系统默认值
        CONTEXT_FILLED    // 上下文补全(SessionContext.lastTemperature 等)
    }

    private final String stepId;
    private final String capabilityName;
    private final Map<String, Object> arguments;
    private final Map<String, ArgumentProvenance> argumentProvenance;

    public ToolCall(String capabilityName, Map<String, Object> arguments) {
        this(UUID.randomUUID().toString(), capabilityName, arguments, Collections.emptyMap());
    }

    public ToolCall(String capabilityName, Map<String, Object> arguments,
            Map<String, ArgumentProvenance> provenance) {
        this(UUID.randomUUID().toString(), capabilityName, arguments, provenance);
    }

    /**
     * 显式传入 stepId 的工厂方法。仅在 Provider Adapter 需要保留远端 tool call id 时使用
     * (例如 Anthropic tool_use 块的 toolu_xxx 必须原样回传给 tool_result)。
     *
     * <p>stepId 不能为空。空 ID 会被 {@code UUID.randomUUID()} 静默补值,
     * Runtime 因此无法区分 ID 是模型返回还是本地伪造——非法 Provider 响应必须显式失败。
     * 缺失、空字符串、blank 都将抛 {@link IllegalArgumentException}。
     */
    public static ToolCall withId(String stepId, String capabilityName, Map<String, Object> arguments) {
        requireNonBlankId(stepId);
        return new ToolCall(stepId, capabilityName, arguments, Collections.emptyMap());
    }

    /** 带 stepId + provenance 的工厂方法,供 Provider Adapter 在已知来源时使用。 */
    public static ToolCall withId(String stepId, String capabilityName,
            Map<String, Object> arguments, Map<String, ArgumentProvenance> provenance) {
        requireNonBlankId(stepId);
        return new ToolCall(stepId, capabilityName, arguments, provenance);
    }

    private static void requireNonBlankId(String stepId) {
        if (stepId == null || stepId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Tool Call ID 不能为空:Provider 必须返回有效的 tool_calls[].id");
        }
    }

    private ToolCall(String stepId, String capabilityName, Map<String, Object> arguments,
            Map<String, ArgumentProvenance> provenance) {
        // 外部通过 withId(...) 调用时,stepId 必须非空(否则是 Provider 协议错误,
        // 必须显式失败,不能静默补 UUID 掩盖问题)。
        // 内部 new ToolCall(cap, args) 与 new ToolCall(cap, args, provenance) 仍走自动 UUID,
        // 因为这些是 Runtime 自造的 ToolCall(DemoModelGateway / 测试),不涉及协议。
        this.stepId = stepId;
        this.capabilityName = capabilityName;
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.argumentProvenance = provenance == null || provenance.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
    }

    public String getStepId() {
        return stepId;
    }

    public String getCapabilityName() {
        return capabilityName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public Object argument(String key) {
        return arguments.get(key);
    }

    /** 参数来源;未标注的参数默认 {@link ArgumentProvenance#MODEL_INFERRED}。 */
    public ArgumentProvenance provenanceOf(String key) {
        return argumentProvenance.getOrDefault(key, ArgumentProvenance.MODEL_INFERRED);
    }

    public Map<String, ArgumentProvenance> getArgumentProvenance() {
        return argumentProvenance;
    }
}
