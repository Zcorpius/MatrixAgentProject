package com.matrix.agent.task;

import com.matrix.agent.task.persistence.EpisodicSummary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;

/**
 * EpisodicSummary 结构测试——验证 SUCCEEDED 任务的 summary 含
 * 安全字段(时间/终态/持续时间/turnCount/successfulCapabilities),且**不含**
 * PII 字段(userText/assistantContent/toolArguments/toolResultContent)。
 *
 * <p>用户硬约束:"新增 EpisodicSummary... 仅记录时间/类别/终态/持续时间/成功的能力名称/
 * 有限的非敏感结果标签。限制在 1-2KB,最多 3 个能力"。
 */
public final class EpisodicSummaryStructureTest {

    @Test
    public void succeededSummaryContainsSafeFieldsOnly() throws Exception {
        AgentRequest request = AgentRequest.builder("把温度调到 24 度", Actor.DRIVER)
                .sessionId("sess").epoch(0L).build();
        Trajectory trajectory = new Trajectory(1234567890L);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, trajectory, 1500L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);

        assertFalse("SUCCEEDED 不应 skip", summary.shouldSkip());
        assertEquals(1234567890L, summary.getStartedAtMillis());
        assertEquals("SUCCEEDED", summary.getFinalState());
        assertEquals(1500L, summary.getDurationMs());
        assertEquals(0, summary.getTurnCount());  // 空 trajectory

        JSONObject json = new JSONObject(summary.toJson());
        assertEquals("SUCCEEDED", json.getString("finalState"));
        assertEquals(1234567890L, json.getLong("startedAtMillis"));
        assertEquals(1500L, json.getLong("durationMs"));
        assertEquals(0, json.getInt("turnCount"));
        assertTrue("successfulCapabilities 存在(可能空数组)",
                json.has("successfulCapabilities"));
    }

    @Test
    public void summaryExcludesUserTextAndToolArguments() {
        AgentRequest request = AgentRequest.builder("我的家庭地址是中关村大街 1 号", Actor.DRIVER)
                .sessionId("sess-pii").epoch(0L).build();
        Trajectory trajectory = new Trajectory(1L);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, trajectory, 100L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);
        String json = summary.toJson();

        // PII 不应在 summary 里
        assertFalse("userText(家庭地址)不进 summary: " + json,
                json.contains("中关村") || json.contains("家庭地址"));
        assertFalse("summary 不含 assistantContent 字段: " + json,
                json.contains("assistantContent"));
        assertFalse("summary 不含 arguments 字段: " + json,
                json.contains("\"arguments\""));
        assertFalse("summary 不含 observations 字段: " + json,
                json.contains("observations"));
    }

    @Test
    public void failedSummaryAlsoPersisted() {
        AgentRequest request = AgentRequest.builder("测试失败", Actor.DRIVER).build();
        Trajectory trajectory = new Trajectory(99L);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.FAILED, StopReason.POLICY_HALT, trajectory, 200L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);

        assertFalse("FAILED 也不 skip(用户指定 SUCCEEDED+FAILED 都写)",
                summary.shouldSkip());
        assertEquals("FAILED", summary.getFinalState());
        assertEquals(99L, summary.getStartedAtMillis());
    }

    @Test
    public void nullInputsReturnSkipStub() {
        EpisodicSummary nullReq = EpisodicSummary.build(null, null);
        assertTrue("null request/outcome → skip", nullReq.shouldSkip());
        assertEquals("", nullReq.toJson());
    }

    @Test
    public void emptyTrajectoryYieldsZeroFields() {
        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER).build();
        // AgentOutcome 拒绝 null trajectory,传一个空 trajectory
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(0L), 0L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);
        assertEquals(0L, summary.getStartedAtMillis());
        assertEquals(0, summary.getTurnCount());
        assertEquals(Collections.emptyList(), summary.getSuccessfulCapabilities());
    }
}
