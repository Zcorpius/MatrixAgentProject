package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.intent.IntentClassifier;

import com.matrix.agent.contract.FinishReason;

/** 终止原因:Agent Loop 退出时由 Runtime 给出的结构化判定。 */
public enum StopReason {
    /** 模型选择直接答复用户,正常完成。 */
    DONE,
    /** 模型返回不带 tool_call,等同于 DONE,独立列出便于诊断。 */
    NO_TOOL_CALL,
    /** 超过最大迭代数。 */
    MAX_ITERATIONS,
    /** 超过累计 tool call 上限。 */
    MAX_TOOL_CALLS,
    /** 请求 deadline 到。 */
    TIMEOUT,
    /** 模型请求未能建立网络连接；与真正达到请求 deadline 的 {@link #TIMEOUT} 不同。 */
    NETWORK_UNAVAILABLE,
    /** 用户取消。 */
    CANCELLED,
    /**
     * 用户通过 {@link Steer}(Type=DEFER)显式推迟当前任务。
     * Loop 立即终止,TaskState 对应 {@link TaskState#DEFERRED}。
     * 区别于 {@link #CANCELLED}:被推迟的任务语义上可被重新调度,不是失败。
     */
    DEFERRED,
    /**
     * 被主驾更高优先级任务抢占。
     *
     * <p>区别于 {@link #CANCELLED}:抢占语义上不是用户主动取消。
     * 旧契约把映射责任推给调用方,改为 TaskScheduler 内部识别
     * (token 由 Scheduler 自己 cancel 的 task)→ StopReason 重映射为 PREEMPTED,
     * TaskState 对应 {@link TaskState#PREEMPTED}。
     */
    PREEMPTED,
    /** 能力级拒绝反复出现或安全检查点强制终止。 */
    POLICY_HALT,
    /** 字符预算(总输入字符上限)耗尽。 */
    BUDGET_EXHAUSTED,
    /**
     * 模型输出因 {@code max_tokens} 截断(Provider finish_reason=length /
     * Anthropic stop_reason=max_tokens)。Observation 可能不完整,不能视为成功。
     */
    LENGTH_EXCEEDED,
    /**
     * Provider 协议不一致——{@code finish_reason}/{@code stop_reason} 缺失或未知
     * (映射为 {@link FinishReason#NONE}),或 STOP 但 content 为空。Runtime 不能信任
     * 这样的输出,直接终止任务,不允许被错判为正常完成。
     *
     * <p>旧实现把 NONE 一律当作 NO_TOOL_CALL → 可能 SUCCEEDED,
     * 把协议错误掩盖成正常完成。
     */
    PROTOCOL_ERROR,
    /**
     * 写命令已下发但执行结果未知——Repository 外层 deadline/interrupt
     * 触发后,token.cancel + grace window 仍未让 Engine 返回结果,而 IntentClassifier
     * 判定用户输入为写操作(intentReadOnly=false)。对应 {@link TaskState#EXECUTION_UNKNOWN}。
     *
     * <p>与 {@link #TIMEOUT} / {@link #CANCELLED} 的区别:这两个 reason 暗示"操作未发生",
     * 而本 reason 明确表达"命令已下发但结果未知",UI/Audit 必须按未知态展示。
     */
    EXECUTION_UNKNOWN,
    /**
     * 任务在调度层就被拒绝(schedulerPool 队列满)。对应 {@link TaskState#REJECTED}。
     *
     * <p>区别于 {@link #POLICY_HALT}(能力级拒绝反复出现):REJECTED 是系统过载,任务从未
     * 进入 Loop,也未下发给 Provider / Capability。UI 应提示"系统繁忙,请稍后重试"。
     */
    REJECTED
}
