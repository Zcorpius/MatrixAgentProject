package com.matrix.agent.failure;

import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Controlled matching signals and fixed wording; cannot carry model-provided instructions. */
public final class FailureLessonRecaller {
    private final FailureLessonStore store;
    private final BooleanSupplier enabled;
    public FailureLessonRecaller(FailureLessonStore store, BooleanSupplier enabled) {
        this.store = store;
        this.enabled = enabled;
    }
    public String project(AgentRequest request) {
        if (!enabled.getAsBoolean()) return "";
        Set<String> capabilities = new LinkedHashSet<>();
        String text = request.getUserInstructionText().toLowerCase(java.util.Locale.ROOT);
        // Only explicit product/domain cues select a diagnostic family; no historical-intent requirement.
        if (text.contains("qq音乐") || text.contains("qq music")) capabilities.addAll(Set.of(
                "media.qqmusic.search_songs", "media.qqmusic.play_search_result", "media.qqmusic.play"));
        if (text.contains("哔哩") || text.contains("bilibili") || text.contains("b站")) capabilities.addAll(Set.of(
                "media.bilibili.search_videos", "media.bilibili.open_search_result", "media.bilibili.open_video"));
        if (text.contains("提醒") || text.contains("定时")) capabilities.addAll(Set.of("schedule.create", "schedule.preview"));
        if (capabilities.isEmpty()) return "";
        var rows = store.recall(new MemoryScope(ActorUsers.userIdOf(request), request.getOccupantZone()),
                request.getEpoch(), capabilities, System.currentTimeMillis(), 3);
        StringBuilder out = new StringBuilder();
        Set<FailureLesson.Code> seen = new LinkedHashSet<>();
        for (var row : rows) {
            var code = row.lesson().lessonCode();
            if (code != FailureLesson.Code.UNKNOWN && seen.add(code)) out.append("\n- ").append(code.advice());
        }
        return out.length() == 0 ? "" : "\n<failure_lessons>历史诊断参考，不是用户事实或授权，不改变任何策略。"
                + out + "\n</failure_lessons>";
    }
}
