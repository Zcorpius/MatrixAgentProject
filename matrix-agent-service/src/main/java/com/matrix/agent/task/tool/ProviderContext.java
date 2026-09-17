package com.matrix.agent.task.tool;

import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.ActorUsers;
import com.matrix.agent.data.memory.MemoryStore;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * capability handler 执行期的不可变上下文。
 *
 * <p>封装 handler 需要的全部运行时状态:
 * <ul>
 *   <li>{@code request} / {@code call} —— 当前请求和 Tool 调用</li>
 *   <li>{@code observedState} / {@code commandedState} —— Provider 内部业务状态(live ref,
 *       handler 直接读写,无需返回值传递)</li>
 *   <li>{@code memoryStore} —— memory.preference.* 后端</li>
 *   <li>{@code memoryWriter} —— memory.semantic.* 后端(NOOP 时 semantic handler
 *       写入失败,fail-log 不影响主路径)</li>
 *   <li>{@code failNextVehicleReadback} —— 测试钩子(live ref,handler 通过
 *       {@link #commandAndApply} 自动消费)</li>
 * </ul>
 *
 * <p>线程安全:每个 Agent Looper 任务在自己的 thread 跑,Provider 内部 Map/AtomicBoolean
 * 用 ConcurrentHashMap / AtomicBoolean 保证并发安全。
 */
public final class ProviderContext {
    private final AgentRequest request;
    private final ToolCall call;
    private final Map<String, Object> observedState;
    private final Map<String, Object> commandedState;
    private final MemoryStore memoryStore;
    private final MemoryWriter memoryWriter;
    private final AtomicBoolean failNextVehicleReadback;

    public ProviderContext(AgentRequest request, ToolCall call,
            Map<String, Object> observedState, Map<String, Object> commandedState,
            MemoryStore memoryStore, AtomicBoolean failNextVehicleReadback) {
        this(request, call, observedState, commandedState, memoryStore,
                MemoryWriter.NOOP, failNextVehicleReadback);
    }

    /**
     * 7 参构造器,带 memoryWriter(memory.semantic.* 后端)。
     *
     * <p>旧 6 参构造器 delegate 到本方法,memoryWriter 默认 {@link MemoryWriter#NOOP}——
     * 所有测试 fake 调用点不需改,保 654 测试零回归。
     */
    public ProviderContext(AgentRequest request, ToolCall call,
            Map<String, Object> observedState, Map<String, Object> commandedState,
            MemoryStore memoryStore, MemoryWriter memoryWriter,
            AtomicBoolean failNextVehicleReadback) {
        if (request == null) throw new IllegalArgumentException("request 不能为空");
        if (call == null) throw new IllegalArgumentException("call 不能为空");
        if (observedState == null) throw new IllegalArgumentException("observedState 不能为空");
        if (commandedState == null) throw new IllegalArgumentException("commandedState 不能为空");
        if (memoryStore == null) throw new IllegalArgumentException("memoryStore 不能为空");
        if (memoryWriter == null) throw new IllegalArgumentException("memoryWriter 不能为空");
        if (failNextVehicleReadback == null) {
            throw new IllegalArgumentException("failNextVehicleReadback 不能为空");
        }
        this.request = request;
        this.call = call;
        this.observedState = observedState;
        this.commandedState = commandedState;
        this.memoryStore = memoryStore;
        this.memoryWriter = memoryWriter;
        this.failNextVehicleReadback = failNextVehicleReadback;
    }

    public AgentRequest getRequest() { return request; }
    public ToolCall getCall() { return call; }
    public Map<String, Object> getObservedState() { return observedState; }
    public Map<String, Object> getCommandedState() { return commandedState; }
    public MemoryStore getMemoryStore() { return memoryStore; }
    public MemoryWriter getMemoryWriter() { return memoryWriter; }

    /** Uses the single Actor-to-user projection shared by task, memory and audit domains. */
    public String userId() {
        return ActorUsers.userIdOf(request);
    }

    /** Canonical memory access scope for the request's resolved occupant zone. */
    public MemoryScope memoryScope() {
        return new MemoryScope(userId(), request.getOccupantZone());
    }

    /**
     * 写 commandedState 并(默认)同步到 observedState。
     *
     * <p>{@code failNextVehicleReadback} 被设置时跳过 observedState 同步——模拟"命令已下发
     * 但回读失败"场景,用于触发 AgentEngine 的 VERIFICATION_FAILED 路径。
     */
    public void commandAndApply(String key, Object value) {
        commandedState.put(key, value);
        if (failNextVehicleReadback.getAndSet(false)) {
            android.util.Log.w("MatrixAgent",
                    "[Provider] readback intentionally skipped for key=" + key);
            return;
        }
        observedState.put(key, commandedState.get(key));
    }
}
