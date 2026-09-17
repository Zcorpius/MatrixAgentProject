package com.matrix.agent.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户在 Agent Loop 进行中追加的转向指令。
 *
 * <p>区别于 {@code AgentRequest}(任务级)和 {@code CancellationToken}(终止信号),
 * Steer 是 Loop 内部消费的"运行时干预":
 * <ul>
 *   <li>{@link Type#REPROMPT}:用户追加自然语言,合并到 conversation 作为新 user message,
 *       让 Loop 在下一轮 LLM 看到补充上下文(例:"等下还要打开座椅加热")。</li>
 *   <li>{@link Type#FORCE_TOOL}:用户强制指定下一轮 Tool Call,跳过 LLM 直接执行
 *       (例:用户看到模型答非所问,直接说"就调用 navigation.start_route")。</li>
 *   <li>{@link Type#DEFER}:用户要求推迟当前任务,Loop 立即终止,
 *       {@code StopReason.DEFERRED} + {@code TaskState.DEFERRED}。</li>
 * </ul>
 *
 * <p>不可变。线程安全由 {@link SteerMailbox} 的 ConcurrentLinkedQueue 保证。
 */
public final class Steer {
    public enum Type { REPROMPT, FORCE_TOOL, DEFER }

    private final Type type;
    private final String payload;
    private final Map<String, Object> arguments;
    private final long createdAtMillis;

    private Steer(Type type, String payload, Map<String, Object> arguments, long createdAtMillis) {
        if (type == null) throw new IllegalArgumentException("type 不能为空");
        this.type = type;
        this.payload = payload == null ? "" : payload;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        this.createdAtMillis = createdAtMillis;
    }

    public static Steer reprompt(String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            throw new IllegalArgumentException("REPROMPT payload 不能为空");
        }
        return new Steer(Type.REPROMPT, userText, Collections.<String, Object>emptyMap(),
                System.currentTimeMillis());
    }

    public static Steer forceTool(String capabilityName, Map<String, Object> arguments) {
        if (capabilityName == null || capabilityName.trim().isEmpty()) {
            throw new IllegalArgumentException("FORCE_TOOL capabilityName 不能为空");
        }
        return new Steer(Type.FORCE_TOOL, capabilityName, arguments, System.currentTimeMillis());
    }

    public static Steer defer() {
        return new Steer(Type.DEFER, "", Collections.<String, Object>emptyMap(),
                System.currentTimeMillis());
    }

    public Type getType() { return type; }
    public String getPayload() { return payload; }
    public Map<String, Object> getArguments() { return arguments; }
    public long getCreatedAtMillis() { return createdAtMillis; }

    @Override
    public String toString() {
        return "Steer{type=" + type + ", payload='" + payload + "', args=" + arguments + "}";
    }
}
