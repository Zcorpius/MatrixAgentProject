package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

/**
 * TaskState.REJECTED + StopReason.REJECTED 枚举契约测试。
 *
 * <p>评审发现共享池嵌套等待死锁,改为两独立池 + 显式拒绝处理。
 * Scheduler / ToolExecutor 在 RejectedExecutionException 时返回 REJECTED 终态,
 * Repository 兜底 audit 自动覆盖(终态审计)。
 *
 * <p>本测试验证:
 * <ul>
 *   <li>TaskState.REJECTED 存在且唯一;</li>
 *   <li>StopReason.REJECTED 存在且唯一;</li>
 *   <li>枚举值可通过 TaskState.valueOf / StopReason.valueOf 反序列化(审计 / DB 序列化兼容)。</li>
 * </ul>
 */
public final class TaskStateRejectedContractTest {

    @Test
    public void taskStateRejectedExists() {
        Set<TaskState> all = EnumSet.allOf(TaskState.class);
        assertTrue("TaskState.REJECTED 必须存在(评审 P1-1 新增)", all.contains(TaskState.REJECTED));
    }

    @Test
    public void stopReasonRejectedExists() {
        Set<StopReason> all = EnumSet.allOf(StopReason.class);
        assertTrue("StopReason.REJECTED 必须存在(评审 P1-1 新增)", all.contains(StopReason.REJECTED));
    }

    @Test
    public void valueOfRejectedRoundTrips() {
        // 审计 / DB 序列化用 name() 字符串
        assertEquals("REJECTED", TaskState.REJECTED.name());
        assertEquals("REJECTED", StopReason.REJECTED.name());
        assertEquals(TaskState.REJECTED, TaskState.valueOf("REJECTED"));
        assertEquals(StopReason.REJECTED, StopReason.valueOf("REJECTED"));
    }

    @Test
    public void rejectedDistinctFromOtherTerminalStates() {
        // REJECTED 是"任务从未真正执行",区别于 FAILED(命令失败) / CANCELLED(用户取消) / TIMED_OUT(超时)
        assertTrue(TaskState.REJECTED != TaskState.FAILED);
        assertTrue(TaskState.REJECTED != TaskState.CANCELLED);
        assertTrue(TaskState.REJECTED != TaskState.TIMED_OUT);
        assertTrue(TaskState.REJECTED != TaskState.EXECUTION_UNKNOWN);
    }
}
