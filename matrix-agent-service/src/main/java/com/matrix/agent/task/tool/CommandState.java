package com.matrix.agent.task.tool;

/**
 * 已下发命令的执行状态——让 Runtime 区分"命令已发但结果未知"
 * 与"命令已取消 / 已完成 / 已失败"。
 *
 * <p>背景:旧版的 {@code ToolExecutor} 在 timeout / cancel 时执行 {@code future.cancel(true)}
 * 并直接返回 {@link ToolResult.Status#CANCELLED} / {@link ToolResult.Status#TIMED_OUT},
 * 对读操作没问题(读操作被中断即等于未发生);但写操作下发到 AIDL/Binder/Intent/厂商 SDK 后,
 * Java 线程中断<b>不保证</b>取消已发出的 IPC 命令——空调/座椅/导航 Provider 可能在 Runtime
 * 已返回"已取消"后继续设置车辆状态,导致 Agent Trajectory 与真实车辆状态不一致。
 *
 * <p>协议定义 + 默认行为退化({@link CapabilityProvider#queryCommandState} 默认 UNKNOWN);
 * 后续版本接真实 Provider 时按 capability 实现查询,Runtime 在 EXECUTION_UNKNOWN
 * 终态后异步 readback 更新为 SUCCESS / VERIFICATION_FAILED / UNKNOWN。
 *
 * <ul>
 *   <li>{@link #SUBMITTED}——命令已下发到 Provider,但 Provider 尚未确认执行</li>
 *   <li>{@link #EXECUTING}——Provider 确认命令正在执行(例如车控 ECU 已开始响应)</li>
 *   <li>{@link #COMPLETED}——命令已完成,readback 字段一致(等价于 ToolResult.Status.SUCCESS)</li>
 *   <li>{@link #FAILED}——命令执行失败(ECU 拒绝 / 物理执行器故障 / 超时)</li>
 *   <li>{@link #UNKNOWN}——Provider 不支持状态查询,Runtime 必须按 EXECUTION_UNKNOWN 处理</li>
 * </ul>
 */
public enum CommandState {
    SUBMITTED,
    EXECUTING,
    COMPLETED,
    FAILED,
    UNKNOWN
}
