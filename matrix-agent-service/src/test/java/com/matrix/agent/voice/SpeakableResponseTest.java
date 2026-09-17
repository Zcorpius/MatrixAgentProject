package com.matrix.agent.voice;

import com.matrix.agent.task.TaskState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** {@link SpeakableResponse} 值对象测试。 */
public final class SpeakableResponseTest {

    @Test
    public void fieldsAndCharCount() {
        SpeakableResponse r = new SpeakableResponse("已停止。", "zh-CN", TaskState.CANCELLED);
        assertEquals("已停止。", r.getText());
        assertEquals("zh-CN", r.getLanguageTag());
        assertEquals(TaskState.CANCELLED, r.getTerminalState());
        assertEquals(4, r.getCharCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullTextRejected() {
        new SpeakableResponse(null, "zh-CN", TaskState.SUCCEEDED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullLanguageTagRejected() {
        new SpeakableResponse("ok", null, TaskState.SUCCEEDED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullTerminalStateRejected() {
        new SpeakableResponse("ok", "zh-CN", null);
    }

    @Test
    public void emptyTextAllowed_forSilentCancel() {
        // CANCELLED 静默场景:Controller 据空文本跳过 TTS。
        SpeakableResponse r = new SpeakableResponse("", "zh-CN", TaskState.CANCELLED);
        assertEquals("", r.getText());
        assertEquals(0, r.getCharCount());
    }
}
