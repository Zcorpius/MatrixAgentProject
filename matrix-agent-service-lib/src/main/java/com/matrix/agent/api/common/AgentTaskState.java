package com.matrix.agent.api.common;

/**
 * Agent 任务状态机（跨进程契约；与架构文档 §3.4.3 状态表一致）。
 *
 * <pre>
 * ACCEPTED → RUNNING → WAITING_CONFIRMATION → RUNNING → 终态
 *                       RUNNING → DEFERRED → RUNNING(仅 resumeTask) / CANCELLED
 * 终态：COMPLETED / PARTIALLY_COMPLETED / REJECTED / FAILED / CANCELLED
 * EXECUTION_UNKNOWN：写命令可能已下发且无可信最终状态，仅 recovery/readback 可修订。
 * </pre>
 */
public final class AgentTaskState {

    public static final int ACCEPTED = 0;
    public static final int RUNNING = 1;
    public static final int WAITING_CONFIRMATION = 2;
    public static final int DEFERRED = 3;
    public static final int COMPLETED = 4;
    public static final int PARTIALLY_COMPLETED = 5;
    public static final int REJECTED = 6;
    public static final int FAILED = 7;
    public static final int CANCELLED = 8;
    public static final int EXECUTION_UNKNOWN = 9;

    public static boolean isTerminal(int state) {
        return state == COMPLETED || state == PARTIALLY_COMPLETED
                || state == REJECTED || state == FAILED || state == CANCELLED;
    }

    private AgentTaskState() {
    }
}
