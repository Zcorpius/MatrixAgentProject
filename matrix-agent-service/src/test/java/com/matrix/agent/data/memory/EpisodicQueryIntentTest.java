package com.matrix.agent.data.memory;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.List;

public final class EpisodicQueryIntentTest {
    @Test public void spatialAndCurrentInstructionsDoNotRecallPastEvents() {
        for (String text : List.of("最近的充电站导航过去", "最近的充电站在哪里，导航过去", "导航到最近的医院", "把空调调低", "过去接人")) {
            assertFalse(text, EpisodicQueryIntent.isHistoryQuestion(text));
        }
    }

    @Test public void historicalQuestionsStillRecallWithOrWithoutCategory() {
        for (String text : List.of("上次导航去了哪", "上次做过什么", "刚才空调多少度",
                "最近导航去了哪里", "过去有哪些记录", "what did I do earlier", "last time")) {
            assertTrue(text, EpisodicQueryIntent.isHistoryQuestion(text));
        }
    }
    @Test public void complementsAndProcessNounsDoNotImplyPastExperience() {
        for (String text : List.of("把音量调过高了，请降低", "别把音量调过低", "播放过程中保持音量",
                "调过点再停", "导航过去", "调过来")) {
            assertFalse(text, EpisodicQueryIntent.isHistoryQuestion(text));
        }
        for (String text : List.of("你调过音量吗", "播放过什么", "上次调过多少度")) {
            assertTrue(text, EpisodicQueryIntent.isHistoryQuestion(text));
        }
    }

}
