package com.matrix.agent.task;

public enum TaskState {
    RECEIVED,
    UNDERSTANDING,
    PLANNED,
    EXECUTING,
    VERIFYING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    /** 用户通过 {@link Steer}(Type=DEFER)推迟当前任务。 */
    DEFERRED,
    /**
     * 被主驾更高优先级任务抢占。
     *
     * <p>区别于 {@link #CANCELLED}:抢占语义上不是用户主动取消,
     * 而是更高优先级请求插队——副驾查询被主驾查询抢占,StopReason 仍是 CANCELLED。
     */
    PREEMPTED,
    /**
     * 写命令已下发但执行结果未知——Repository 外层 deadline/interrupt
     * 触发后,token.cancel + grace window 仍未让 Engine 返回结果(ToolExecutor 来不及
     * 生成 EXECUTION_UNKNOWN),而用户输入被 IntentClassifier 判定为写操作(intentReadOnly=false)。
     *
     * <p>语义:不能等同于 {@link #FAILED}(命令可能成功) / {@link #CANCELLED}(命令可能已执行) /
     * {@link #TIMED_OUT}(命令可能已下发);UI 必须按"未知终态"展示,后续版本由
     * readback / queryCommandState 异步更新为 SUCCEEDED / FAILED。
     */
    EXECUTION_UNKNOWN,
    /**
     * 任务被调度器拒绝(schedulerPool 队列满 / Executor 拒绝)。
     *
     * <p>区别于 {@link #FAILED}(命令本身失败)/ {@link #CANCELLED}(用户取消)/ {@link #TIMED_OUT}
     * (超时):REJECTED 是系统过载,任务从未真正执行——没下发给 Provider,也没下发给 Capability。
     * UI 应提示"系统繁忙,请稍后重试"。
     *
     * <p>与 {@link #EXECUTION_UNKNOWN} 同模式(新增枚举值,Repository 兜底 audit 自动覆盖)。
     */
    REJECTED
}
