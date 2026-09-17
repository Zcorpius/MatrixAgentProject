package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;

/**
 * EpisodicSummary 终态过滤——仅 SUCCEEDED / FAILED 写入。
 *
 * <p>用户硬约束:"仅记录 SUCCEEDED、FAILED... 取消和超时默认不写"。CANCELLED / TIMED_OUT /
 * PREEMPTED / REJECTED / PROTOCOL_ERROR / EXECUTION_UNKNOWN / DEFERRED / PARTIALLY_SUCCEEDED 全部 skip。
 *
 * <p>PARTIALLY_SUCCEEDED 从 persisted 集合移除——实现时擅自加入,
 * 不在用户确认的范围内。语义上部分成功不代表完整意图,且 finalState 字段会让模型误判为
 * "已执行"参考。若用户确认要写入再加回。
 */
public final class EpisodicSummarySkipStatesTest {

    @Test
    public void cancelledSkipped() {
        EpisodicSummary s = buildWithState(TaskState.CANCELLED, StopReason.CANCELLED);
        assertTrue("CANCELLED 应 skip", s.shouldSkip());
    }

    @Test
    public void timedOutSkipped() {
        EpisodicSummary s = buildWithState(TaskState.TIMED_OUT, StopReason.TIMEOUT);
        assertTrue("TIMED_OUT 应 skip", s.shouldSkip());
    }

    @Test
    public void preemptedSkipped() {
        EpisodicSummary s = buildWithState(TaskState.PREEMPTED, StopReason.PREEMPTED);
        assertTrue("PREEMPTED 应 skip", s.shouldSkip());
    }

    @Test
    public void rejectedSkipped() {
        EpisodicSummary s = buildWithState(TaskState.REJECTED, StopReason.REJECTED);
        assertTrue("REJECTED 应 skip", s.shouldSkip());
    }

    @Test
    public void executionUnknownSkipped() {
        EpisodicSummary s = buildWithState(TaskState.EXECUTION_UNKNOWN, StopReason.EXECUTION_UNKNOWN);
        assertTrue("EXECUTION_UNKNOWN 应 skip", s.shouldSkip());
    }

    @Test
    public void deferredSkipped() {
        EpisodicSummary s = buildWithState(TaskState.DEFERRED, StopReason.DEFERRED);
        assertTrue("DEFERRED 应 skip", s.shouldSkip());
    }

    @Test
    public void succeededNotSkipped() {
        EpisodicSummary s = buildWithState(TaskState.SUCCEEDED, StopReason.DONE);
        assertTrue("SUCCEEDED 不应 skip", !s.shouldSkip());
        assertEquals("SUCCEEDED", s.getFinalState());
    }

    @Test
    public void failedNotSkipped() {
        EpisodicSummary s = buildWithState(TaskState.FAILED, StopReason.POLICY_HALT);
        assertTrue("FAILED 不应 skip", !s.shouldSkip());
        assertEquals("FAILED", s.getFinalState());
    }

    @Test
    public void partiallySucceededSkipped() {
        // PARTIALLY_SUCCEEDED 不在用户确认的 persisted 集合内,skip=true
        EpisodicSummary s = buildWithState(TaskState.PARTIALLY_SUCCEEDED, StopReason.DONE);
        assertTrue("V0.5.5 后 PARTIALLY_SUCCEEDED 应 skip(与 CANCELLED / TIMED_OUT 同)",
                s.shouldSkip());
    }

    private static EpisodicSummary buildWithState(TaskState state, StopReason reason) {
        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                state, reason, new Trajectory(1L), 100L);
        return EpisodicSummary.build(request, outcome);
    }
}
