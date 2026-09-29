package com.matrix.agent.schedule.domain;

import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.api.schedule.ScheduleCodes;
import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.api.schedule.ScheduleTiming;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeSet;

/** One normalizer for SDK, UI and tool entry points. Authorization follows normalization in Host. */
public final class ScheduleNormalizer {
    public static final long DEFAULT_GRACE_MILLIS = 10 * 60_000L;
    private static final long MAX_DELAY = 366L * 24 * 60 * 60_000;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm")
            .withResolverStyle(ResolverStyle.SMART);

    public record Normalized(ScheduleSpec spec, TimeRule rule) { }

    public Normalized normalize(ScheduleSpec input, ClockSample clock) {
        return normalize(input, clock, true);
    }

    /** The store separately checks changed time rules; metadata edits may target completed plans. */
    public Normalized normalizeForUpdate(ScheduleSpec input, ClockSample clock) {
        return normalize(input, clock, false);
    }

    private Normalized normalize(ScheduleSpec input, ClockSample clock, boolean requireFuture) {
        if (input == null || input.timing == null || input.action == null) fail("计划内容不完整");
        requireSchema(input.schemaVersion);
        requireSchema(input.timing.schemaVersion);
        requireSchema(input.action.schemaVersion);
        String title = text(input.title, 80, 320, "标题");
        ScheduleAction action = normalizeAction(input.action);
        if (input.graceMillis < 0 || input.graceMillis > 24 * 60 * 60_000L) fail("允许迟到窗口必须在 0 至 24 小时内");
        if (action.kind == ScheduleCodes.WORKFLOW && com.matrix.agent.schedule.workflow.ResearchWorkflow.ID.equals(action.templateId)
                && input.graceMillis < com.matrix.agent.identity.ExecutionProfile.RESEARCH.maxActiveMillis()) fail("研究模板的触发后到期窗口至少为 30 分钟（含排队与执行）");
        if (input.misfirePolicy < ScheduleCodes.SKIP || input.misfirePolicy > ScheduleCodes.COALESCE_LATEST) fail("未知补跑策略");

        ScheduleTiming t = input.timing;
        ZoneId zone;
        try { zone = ZoneId.of(t.zoneId); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("请选择有效时区"); }
        TimeRule rule;
        ScheduleTiming timing;
        switch (t.kind) {
            case ScheduleCodes.ONCE -> {
                if (t.atMillis <= 0 || (requireFuture && t.atMillis <= clock.wall().toEpochMilli())) fail("一次性计划必须指定未来时刻");
                rule = new TimeRule.Once(Instant.ofEpochMilli(t.atMillis));
                timing = timing(t.kind, zone, t.atMillis, 0, "", 0, "", "", false, "", 0);
            }
            case ScheduleCodes.AFTER_DELAY -> {
                if (t.delayMillis < 60_000 || t.delayMillis > MAX_DELAY) fail("相对延迟必须在 1 分钟至 366 天内");
                rule = new TimeRule.AfterDelay(clock.wall().plusMillis(t.delayMillis),
                        Math.addExact(clock.elapsedMillis(), t.delayMillis), clock.bootId());
                timing = timing(t.kind, zone, 0, t.delayMillis, "", 0, "", "", false, "", 0);
            }
            case ScheduleCodes.DAILY, ScheduleCodes.WEEKLY -> {
                if (t.localTime == null || !t.localTime.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) fail("时间格式应为 HH:mm");
                LocalTime time = LocalTime.parse(t.localTime, MINUTE);
                int mask = t.kind == ScheduleCodes.DAILY ? 0 : t.weekdaysMask;
                if (t.kind == ScheduleCodes.WEEKLY && (mask <= 0 || (mask & ~127) != 0)) fail("请选择有效星期");
                LocalDate start = empty(t.startDate) ? clock.wall().atZone(t.followDeviceZone ? clock.deviceZone() : zone).toLocalDate() : LocalDate.parse(t.startDate);
                LocalDate end = empty(t.endDate) ? null : LocalDate.parse(t.endDate);
                EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
                for (DayOfWeek day : DayOfWeek.values()) if ((mask & (1 << (day.getValue() - 1))) != 0) days.add(day);
                rule = new TimeRule.Recurring(time, zone, days, start, end, t.followDeviceZone);
                if (requireFuture && OccurrenceCalculator.next(rule, clock.wall(), 1, "preview", clock).isEmpty()) fail("规则没有未来可执行时刻");
                timing = timing(t.kind, zone, 0, 0, time.toString(), mask, start.toString(),
                        end == null ? "" : end.toString(), t.followDeviceZone, "", 0);
            }
            case ScheduleCodes.CALENDAR_OFFSET -> {
                requireUuid(t.calendarBindingId);
                if (t.calendarOffsetMillis < 0 || t.calendarOffsetMillis > 7L * 24 * 60 * 60_000) fail("日历提前量超出范围");
                rule = new TimeRule.CalendarOffset(t.calendarBindingId, t.calendarOffsetMillis);
                timing = timing(t.kind, zone, 0, 0, "", 0, "", "", false, t.calendarBindingId, t.calendarOffsetMillis);
            }
            default -> throw new IllegalArgumentException("不支持的时间类型");
        }
        return new Normalized(new ScheduleSpec(title, timing, action, input.graceMillis, input.misfirePolicy), rule);
    }

    private ScheduleAction normalizeAction(ScheduleAction input) {
        if (input.kind < ScheduleCodes.NOTIFICATION || input.kind > ScheduleCodes.TOOL) fail("不支持的动作类型");
        String goal = text(input.text, 4096, 8192, "目标");
        String parameters = ScheduleCodec.canonicalObject(input.parametersJson);
        if (parameters.getBytes(StandardCharsets.UTF_8).length > 8192) fail("参数超过 8 KiB");
        if (input.capabilities.size() > 16) fail("授权能力数量超限");
        TreeSet<String> capabilities = new TreeSet<>();
        for (String value : input.capabilities) {
            if (value == null || !value.matches("[a-z][a-z0-9_.]{1,95}")) fail("无效能力名称");
            capabilities.add(value);
        }
        String template = empty(input.templateId) ? "" : input.templateId;
        if (input.kind == ScheduleCodes.WORKFLOW && (!template.matches("[a-z][a-z0-9_.-]{1,63}") || input.templateVersion < 1)) fail("请选择有效模板版本");
        if (input.kind == ScheduleCodes.TOOL && capabilities.size() != 1) fail("确定动作需要唯一能力");
        if (input.kind == ScheduleCodes.NOTIFICATION && (!capabilities.isEmpty() || input.allowNetwork || input.speakResult)) fail("本地提醒不接受外部能力或播报授权");
        ScheduleAction normalized = new ScheduleAction(input.kind, goal, template,
                input.kind == ScheduleCodes.WORKFLOW ? input.templateVersion : 0, parameters,
                List.copyOf(capabilities), input.allowNetwork, input.speakResult);
        if (normalized.kind == ScheduleCodes.WORKFLOW) com.matrix.agent.schedule.workflow.WorkflowCatalog.validate(normalized);
        return normalized;
    }

    public static String text(String value, int chars, int bytes, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        String result = value.strip();
        if (result.length() > chars || result.getBytes(StandardCharsets.UTF_8).length > bytes) {
            throw new IllegalArgumentException(label + "超过长度上限");
        }
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == result.length() || !Character.isLowSurrogate(result.charAt(i))) fail("文本包含无效字符");
            } else if (Character.isLowSurrogate(c) || c == 0) fail("文本包含无效字符");
        }
        return result;
    }

    public static void requireUuid(String value) {
        try {
            if (value == null || !java.util.UUID.fromString(value).toString().equals(value)) fail("需要规范 UUID");
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("需要规范 UUID"); }
    }

    private static void requireSchema(int schema) {
        if (schema != ParcelSchema.CURRENT) fail("计划契约版本不兼容");
    }

    private static ScheduleTiming timing(int kind, ZoneId zone, long at, long delay, String time,
            int days, String start, String end, boolean follow, String binding, long offset) {
        return new ScheduleTiming(kind, zone.getId(), at, delay, time, days, start, end, follow, binding, offset);
    }

    private static boolean empty(String text) { return text == null || text.isEmpty(); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
}
