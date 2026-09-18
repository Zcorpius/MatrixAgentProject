package com.matrix.agent.voice;

import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ResponsePresenter} 测试。
 *
 * <p>话术映射 + 隐私底线(PII 标记注入,断言不进 TTS)+ 截断。
 */
public final class ResponsePresenterTest {
    private final ResponsePresenter presenter = new ResponsePresenter();

    // ---- 测试装配 ----

    private AgentOutcome outcome(TaskState state, StopReason reason, ToolObservation... obs) {
        Trajectory t = new Trajectory();
        if (obs.length > 0) {
            t.addIteration(new AgentIteration(1,
                    AgentMessage.assistant("ok", Collections.emptyList()),
                    Collections.emptyList(),
                    Arrays.asList(obs),
                    Collections.emptyList(),
                    0L));
        }
        t.finish(reason, 0L, obs.length);
        return new AgentOutcome("req-1", state, reason, t, 0L);
    }

    /** 成功 observation。message / observedState 故意塞 PII 标记,验证不被读出。 */
    private ToolObservation successObs(String cap) {
        ToolResult r = new ToolResult(ToolResult.Status.SUCCESS, cap,
                "PII-SECRET-" + cap,
                Collections.singletonMap("dest", "PII-SECRET-value"),
                true, 0L);
        return ToolObservation.fromParts("call-" + cap, cap, r, null, false);
    }

    private ToolObservation failedObs(String cap) {
        ToolResult r = new ToolResult(ToolResult.Status.EXECUTION_FAILED, cap,
                "PII-SECRET-FAIL-" + cap,
                Collections.singletonMap("err", "PII-SECRET-stack"),
                false, 0L);
        return ToolObservation.fromParts("call-" + cap, cap, r, null, false);
    }

    // ---- 话术 ----

    @Test
    public void succeeded_basicMessage() {
        SpeakableResponse r = presenter.present(outcome(TaskState.SUCCEEDED, StopReason.DONE), "zh-CN");
        assertEquals("已完成。", r.getText());
        assertEquals("zh-CN", r.getLanguageTag());
        assertEquals(TaskState.SUCCEEDED, r.getTerminalState());
    }

    @Test
    public void succeeded_singleCapability_usesFriendlyName() {
        AgentOutcome o = outcome(TaskState.SUCCEEDED, StopReason.DONE, successObs("climate.set_temperature"));
        assertEquals("已调整空调温度。", presenter.present(o, "zh-CN").getText());
    }

    @Test
    public void succeeded_unknownCapability_fallsBackToGeneric() {
        // 单个成功但 capability 未知:不走"已{友好名}",回退"已完成。"——避免说出"已这项操作。"
        AgentOutcome o = outcome(TaskState.SUCCEEDED, StopReason.DONE, successObs("unknown.cap"));
        assertEquals("已完成。", presenter.present(o, "zh-CN").getText());
    }

    @Test
    public void partiallySucceeded_countsFromTrajectory() {
        AgentOutcome o = outcome(TaskState.PARTIALLY_SUCCEEDED, StopReason.DONE,
                successObs("climate.set_temperature"), failedObs("navigation.start_route"));
        assertEquals("已完成 1 项,其余 1 项未能完成。", presenter.present(o, "zh-CN").getText());
    }

    @Test
    public void failed_rejected_safePhrasing() {
        assertEquals("为安全起见,我现在不能执行这项操作。",
                presenter.present(outcome(TaskState.FAILED, StopReason.POLICY_HALT), "zh-CN").getText());
        assertEquals("系统繁忙,请稍后重试。",
                presenter.present(outcome(TaskState.REJECTED, StopReason.REJECTED), "zh-CN").getText());
    }

    @Test
    public void executionUnknown_conservativePhrasing() {
        assertEquals("指令可能已发出,建议查看车辆当前状态。",
                presenter.present(outcome(TaskState.EXECUTION_UNKNOWN, StopReason.EXECUTION_UNKNOWN), "zh-CN")
                        .getText());
    }

    @Test
    public void cancelled_silentOrStopped() {
        assertEquals("已停止。",
                presenter.present(outcome(TaskState.CANCELLED, StopReason.CANCELLED), "zh-CN").getText());
        assertEquals("已停止。",
                presenter.present(outcome(TaskState.PREEMPTED, StopReason.PREEMPTED), "zh-CN").getText());
    }

    @Test
    public void timedOut_retryPhrasing() {
        assertEquals("操作超时,请重试。",
                presenter.present(outcome(TaskState.TIMED_OUT, StopReason.TIMEOUT), "zh-CN").getText());
    }

    @Test
    public void deferred_postponePhrasing() {
        assertEquals("稍后再处理。",
                presenter.present(outcome(TaskState.DEFERRED, StopReason.DEFERRED), "zh-CN").getText());
    }

    // ---- 隐私底线 ----

    @Test
    public void privacy_messageNotLeaked() {
        AgentOutcome o = outcome(TaskState.SUCCEEDED, StopReason.DONE, successObs("climate.set_temperature"));
        String text = presenter.present(o, "zh-CN").getText();
        assertFalse("ToolResult.message 不能进 TTS: " + text, text.contains("PII-SECRET"));
    }

    @Test
    public void privacy_internalResultsNotRead() {
        ToolResult secret = new ToolResult(ToolResult.Status.SUCCESS, "climate.set_temperature",
                "PII-SECRET-internal", Collections.singletonMap("k", "PII-SECRET-v"), true, 0L);
        AgentOutcome o = new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE,
                new Trajectory(), 0L, Collections.singletonList(secret));
        String text = presenter.present(o, "zh-CN").getText();
        assertFalse("internalResults 不能进 TTS: " + text, text.contains("PII-SECRET"));
    }

    @Test
    public void privacy_failedMessageNotLeaked() {
        AgentOutcome o = outcome(TaskState.PARTIALLY_SUCCEEDED, StopReason.DONE,
                successObs("climate.set_temperature"), failedObs("navigation.start_route"));
        String text = presenter.present(o, "zh-CN").getText();
        assertFalse("失败 ToolResult.message 不能进 TTS: " + text, text.contains("PII-SECRET"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullOutcome_rejected() {
        presenter.present(null, "zh-CN");
    }

    // ---- 截断 ----

    @Test
    public void maxLength_truncatedAt80() {
        String longText = repeat('啊', 200);
        String truncated = ResponsePresenter.truncate(longText, ResponsePresenter.MAX_TTS_CHARS);
        assertTrue("应截断到 <= MAX_TTS_CHARS: " + truncated.length(),
                truncated.length() <= ResponsePresenter.MAX_TTS_CHARS);
        assertTrue("应以省略号结尾: " + truncated, truncated.endsWith("…"));
    }

    @Test
    public void maxLength_shortTextUntouched() {
        assertEquals("已完成。", ResponsePresenter.truncate("已完成。", ResponsePresenter.MAX_TTS_CHARS));
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        Arrays.fill(arr, c);
        return new String(arr);
    }
}
