package com.matrix.agent.task.capability;

import com.matrix.agent.demo.MockCapabilityProvider;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;
import com.matrix.agent.task.tool.CommandState;
import com.matrix.agent.task.tool.ToolResult;


/**
 * 单个 capability 的执行入口。
 *
 * <p>在不破坏既有实现的前提下,新增 3 个 {@code default} 方法
 * 描述写操作的 abort / 状态查询能力。默认实现退化为"不支持",让 {@link MockCapabilityProvider}
 * 与所有测试 fake 不需要改动;后续版本接真实 AIDL/Binder/厂商 SDK Provider
 * 时按 capability 重写,Runtime 据此决定 timeout / cancel 语义。
 */
public interface CapabilityProvider {
    ToolResult execute(AgentRequest request, ToolCall call);

    /**
     * Provider 是否支持 abort 已下发的命令。
     *
     * <p>默认 {@code false}——Provider 不支持 abort,{@link ToolExecutor} 据此
     * 把写操作超时 / 取消归类为 {@link ToolResult.Status#EXECUTION_UNKNOWN}(不能宣称"已取消")。
     * 后续版本接真实 Provider 时,只在 Provider 真能保证 abort 成功(例如 AIDL
     * 接口有 {@code cancelCommand} 且 Provider 验证过 abort 后 ECU 不会再继续执行)才返回 true。
     *
     * <p>注意:即使返回 true,abort 也不保证成功——Provider 可能在 abort 命令到达前已完成,
     * 或者 ECU 拒绝中断。Runtime 必须在 abort 后仍走 readback / queryCommandState 二次确认。
     */
    default boolean isAbortable() { return false; }

    /**
     * 尽可能 abort 已下发的命令(如果 Provider 支持)。
     *
     * <p>默认空实现——等价于"什么都没做"。{@link ToolExecutor} 在写操作 timeout / cancel 时
     * 调用本方法 + {@code future.cancel(true)} 中断 worker 线程,但<b>不依赖</b> abort 成功:
     * 返回的 {@link ToolResult} 仍然是 {@link ToolResult.Status#EXECUTION_UNKNOWN},
     * 由后续 readback / queryCommandState 决定最终状态。
     *
     * @param commandId 命令 ID(对应 {@link ToolCall#getStepId()}),Provider 用此 ID 在
     *     其内部命令表中查找并 abort;null / 空 ID 时 Provider 可选择 abort 最近一个命令或直接返回
     */
    default void abortIfSupported(String commandId) { }

    /**
     * 查询已下发命令的当前状态。
     *
     * <p>默认 {@link CommandState#UNKNOWN}——Provider 不支持状态查询,Runtime 必须按
     * {@link ToolResult.Status#EXECUTION_UNKNOWN} 处理。后续版本接真实 Provider 时,
     * 此方法让 Runtime 在 timeout / cancel 后异步轮询命令状态,最终更新 ToolResult。
     *
     * @param commandId 命令 ID(对应 {@link ToolCall#getStepId()})
     */
    default CommandState queryCommandState(String commandId) { return CommandState.UNKNOWN; }
}
