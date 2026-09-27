package com.matrix.agent.platform.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.capability.MediaCapabilities;
import org.junit.Test;
import java.util.List;
import java.util.Map;

public final class SongSearchTargetTest {
    @Test public void matchesCompleteNamesForAnyArtistWithoutParsingNaturalLanguage() throws Exception {
        for (var sample : List.of(
                new SongSearchTarget("李健", "消失的月光"),
                new SongSearchTarget("周杰伦", "晴天"),
                new SongSearchTarget("告五人", "带我去找夜生活"),
                new SongSearchTarget("Adele", "Someone Like You"))) {
            var target = SongSearchTarget.from(new ToolCall(MediaCapabilities.QQ_SEARCH,
                    Map.of("query", "模型生成的关键词", "artist", sample.artist(), "title", sample.title())));
            assertEquals(3, target.confirmableIndex(List.of(
                    new QQMusicUiPort.Candidate(3, sample.title(), sample.artist() + "·专辑"))));
        }
    }

    @Test public void versionsCoversAndDuplicateRowsAreNeverSilentlySelected() {
        var target = new SongSearchTarget("李健", "消失的月光");
        var original = new QQMusicUiPort.Candidate(1, "消失的月光", "李健·李健");
        var live = new QQMusicUiPort.Candidate(2, "消失的月光 (Live)", "李健·现场");
        var cover = new QQMusicUiPort.Candidate(3, "消失的月光", "另一位歌手·翻唱");
        assertEquals(1, target.confirmableIndex(List.of(original, live, cover)));
        assertEquals(0, target.confirmableIndex(List.of(live, cover)));
        assertEquals(0, target.confirmableIndex(List.of(original,
                new QQMusicUiPort.Candidate(4, "消失的月光", "李健·另一个专辑"))));
        assertEquals(0, new SongSearchTarget("", "消失的月光")
                .confirmableIndex(List.of(original, cover)));
    }

    @Test public void artistOnlyAndUnstructuredSearchesNeedCandidateSelection() throws Exception {
        var rows = List.of(new QQMusicUiPort.Candidate(1, "传奇", "李健·似水流年"));
        assertEquals(0, new SongSearchTarget("李健", "").confirmableIndex(rows));
        assertEquals(0, SongSearchTarget.from(new ToolCall(MediaCapabilities.QQ_SEARCH,
                Map.of("query", "李健 传奇"))).confirmableIndex(rows));
        assertEquals(1, new SongSearchTarget("", "传奇").confirmableIndex(rows));
    }

    @Test public void normalizesTypographyButDoesNotDropVersionSuffixes() {
        assertEquals(1, new SongSearchTarget("Ａｄｅｌｅ", "Someone   Like You")
                .confirmableIndex(List.of(new QQMusicUiPort.Candidate(
                        1, "Someone Like You", "adele·21"))));
        assertEquals(0, new SongSearchTarget("Adele", "Someone Like You (Live)")
                .confirmableIndex(List.of(new QQMusicUiPort.Candidate(
                        1, "Someone Like You", "Adele·21"))));
    }

    @Test public void invalidEntitiesFailBeforePlatformInteraction() {
        for (Object bad : List.of(42, "a".repeat(65), "歌名\n下一条")) {
            assertThrows(MediaPlatformException.class, () -> SongSearchTarget.from(
                    new ToolCall(MediaCapabilities.QQ_SEARCH, Map.of("query", "歌名", "title", bad))));
        }
    }
}
