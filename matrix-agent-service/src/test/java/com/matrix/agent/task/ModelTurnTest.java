package com.matrix.agent.task;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.FinishReason;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.matrix.agent.contract.ToolCall;

import java.util.Collections;

import org.junit.Test;

/**
 * ModelTurn 工厂方法严格化回归。
 *
 * <p>历史 bug:旧 {@code ModelTurn.of(content, TOOL_CALLS)} 静默降级为 STOP,
 * {@code of("", STOP)} 也接受 —— Provider 协议错误被掩盖成正常完成,
 * AgentEngine 把这种"啥也没说"的轮次当 NO_TOOL_CALL → SUCCEEDED。
 */
public final class ModelTurnTest {
    @Test
    public void ofRejectsToolCallsFinishReason() {
        try {
            ModelTurn.of("不应当作 STOP", FinishReason.TOOL_CALLS);
            fail("of(..., TOOL_CALLS) 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void ofRejectsStopWithEmptyContent() {
        try {
            ModelTurn.of("", FinishReason.STOP);
            fail("of(\"\", STOP) 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            ModelTurn.of("   ", FinishReason.STOP);
            fail("of(空白, STOP) 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            ModelTurn.of(null, FinishReason.STOP);
            fail("of(null, STOP) 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void ofRejectsNullFinishReason() {
        try {
            ModelTurn.of("content", null);
            fail("of(..., null) 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** LENGTH 允许任意 content(可能因截断为空)。 */
    @Test
    public void ofAllowsLengthWithEmptyContent() {
        ModelTurn turn = ModelTurn.of("", FinishReason.LENGTH);
        assertEquals(FinishReason.LENGTH, turn.getFinishReason());
    }

    /** NONE 允许任意 content(协议不一致的兜底,AgentEngine 据此走 PROTOCOL_ERROR)。 */
    @Test
    public void ofAllowsNoneWithEmptyContent() {
        ModelTurn turn = ModelTurn.of("", FinishReason.NONE);
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    /** STOP + 非空 content 是合法的正常完成。 */
    @Test
    public void ofAllowsStopWithNonEmptyContent() {
        ModelTurn turn = ModelTurn.of("已为你完成", FinishReason.STOP);
        assertEquals(FinishReason.STOP, turn.getFinishReason());
    }

    /** directAnswer 也要求非空 content。 */
    @Test
    public void directAnswerRejectsEmptyContent() {
        try {
            ModelTurn.directAnswer("");
            fail("directAnswer(\"\") 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** ofToolCalls 至少需要 1 个 ToolCall。 */
    @Test
    public void ofToolCallsRejectsEmptyList() {
        try {
            ModelTurn.ofToolCalls(Collections.<ToolCall>emptyList(), "content");
            fail("ofToolCalls([], ...) 必须抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
