package com.matrix.agent.schedule.tool;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import static org.junit.Assert.assertThrows;

import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.api.schedule.ScheduleTiming;
import com.matrix.agent.schedule.domain.ClockSample;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.Test;

public final class ExplicitScheduleIntentTest {
    private static final ClockSample CLOCK = new ClockSample(Instant.parse("2026-09-27T00:00:00Z"),
            10_000, "fixture", ZoneId.of("Asia/Shanghai"));

    @Test public void absoluteOffsetPinsInstantAndDefaultZone() {
        long at = Instant.parse("2026-09-28T01:00:00Z").toEpochMilli();
        String user = "设置在2026-09-28T01:00:00+00:00提醒我喝水";
        verify(user, plan(ONCE, "Asia/Shanghai", at, 0, "", 0, "喝水"), true);
        reject(user, plan(ONCE, "Asia/Shanghai", at + 60_000, 0, "", 0, "喝水"), true);
        reject(user, plan(ONCE, "UTC", at, 0, "", 0, "喝水"), true);
        reject(user, plan(ONCE, "Asia/Shanghai", at, 0, "", 0, "休息"), true);
    }

    @Test public void relativeDelayPinsRequestedDuration() {
        String user = "安排20分钟后的喝水提醒";
        verify(user, plan(AFTER_DELAY, "Asia/Shanghai", 0, 20 * 60_000L, "", 0, "喝水"), true);
        reject(user, plan(AFTER_DELAY, "Asia/Shanghai", 0, 30 * 60_000L, "", 0, "喝水"), true);
        reject(user, plan(ONCE, "Asia/Shanghai", 1_790_468_400_000L, 0, "", 0, "喝水"), true);
        verify("五分钟以后提醒我喝水",
                plan(AFTER_DELAY, "Asia/Shanghai", 0, 5 * 60_000L, "", 0, "喝水"), true);
        reject("五分钟以后提醒我喝水",
                plan(AFTER_DELAY, "Asia/Shanghai", 0, 10 * 60_000L, "", 0, "喝水"), true);
    }

    @Test public void weeklyDayAndClockAreBothGrounded() {
        String user = "每周日上海时间09:00提醒我喝水";
        verify(user, plan(WEEKLY, "Asia/Shanghai", 0, 0, "09:00", 1 << 6, "喝水"), true);
        reject(user, plan(WEEKLY, "Asia/Shanghai", 0, 0, "09:00", 1, "喝水"), true);
        reject(user, plan(WEEKLY, "Asia/Shanghai", 0, 0, "09:30", 1 << 6, "喝水"), true);
        reject("每周一至周五09:00提醒我喝水",
                plan(WEEKLY, "Asia/Shanghai", 0, 0, "09:00", 31, "喝水"), true);
    }

    @Test public void dailyNamedZoneCannotFollowDeviceZone() {
        String user = "设置每天America/New_York时区09:30提醒我喝水";
        verify(user, plan(DAILY, "America/New_York", 0, 0, "09:30", 0, "喝水"), true);
        reject(user, plan(DAILY, "Asia/Shanghai", 0, 0, "09:30", 0, "喝水"), true);
        reject("每天晚上提醒我喝水", plan(DAILY, "Asia/Shanghai", 0, 0, "21:00", 0, "喝水"), true);
        reject("每天09:30提醒我喝水", followingDevice("Asia/Shanghai", "09:30"), true);
        verify("每天09:30跟随设备时区提醒我喝水", followingDevice("Asia/Shanghai", "09:30"), true);
    }

    @Test public void previewOnlyNeverCreates() {
        String user = "仅预览10分钟后喝水的提醒，不要保存";
        ScheduleSpec plan = plan(AFTER_DELAY, "Asia/Shanghai", 0, 10 * 60_000L, "", 0, "喝水");
        verify(user, plan, false);
        reject(user, plan, true);
        reject("提醒我喝水，但还没决定时间", plan, true);
        reject("别创建提醒，只想了解功能", plan, true);
    }

    @Test public void chineseClockTimeIsBoundToTheSameMinuteAsNumericTime() {
        verify("制定每天九点十分的闹钟，并告诉我当天的天气情况",
                weatherFollowingDevice("09:10"), true);
        reject("制定每天九点十分的闹钟，并告诉我当天的天气情况",
                weatherFollowingDevice("09:11"), true);
        verify("每天上午九点十分提醒我喝水", plan(DAILY, "Asia/Shanghai", 0, 0, "09:10", 0, "喝水"), true);
        reject("每天九点左右提醒我喝水", plan(DAILY, "Asia/Shanghai", 0, 0, "09:00", 0, "喝水"), true);
        reject("每天九点十分和十点提醒我喝水", plan(DAILY, "Asia/Shanghai", 0, 0, "09:10", 0, "喝水"), true);
    }

    private static ScheduleSpec plan(int kind, String zone, long at, long delay,
            String local, int weekdays, String text) {
        return new ScheduleSpec("喝水", new ScheduleTiming(kind, zone, at, delay, local,
                weekdays, "", "", false, "", 0),
                new ScheduleAction(NOTIFICATION, text, "", 1, "{}", List.of(), false, false),
                600_000, WITHIN_GRACE);
    }

    private static ScheduleSpec followingDevice(String zone, String local) {
        return new ScheduleSpec("喝水", new ScheduleTiming(DAILY, zone, 0, 0, local,
                0, "", "", true, "", 0),
                new ScheduleAction(NOTIFICATION, "喝水", "", 1, "{}", List.of(), false, false),
                600_000, WITHIN_GRACE);
    }

    private static ScheduleSpec weatherFollowingDevice(String local) {
        return new ScheduleSpec("天气", new ScheduleTiming(DAILY, "Asia/Shanghai", 0, 0, local,
                0, "", "", true, "", 0),
                new ScheduleAction(WORKFLOW, "当天的天气情况", "daily_weather_current", 1,
                        "{\"mode\":\"CURRENT_AT_TRIGGER\",\"allowLocation\":true,\"allowWeatherNetwork\":true}",
                        List.of("location.resolve_city", "weather.today"), true, false), 600_000, WITHIN_GRACE);
    }

    private static void verify(String user, ScheduleSpec plan, boolean creates) {
        ExplicitScheduleIntent.verify(user, plan, CLOCK, creates);
    }

    private static void reject(String user, ScheduleSpec plan, boolean creates) {
        assertThrows(IllegalArgumentException.class, () -> verify(user, plan, creates));
    }
}
