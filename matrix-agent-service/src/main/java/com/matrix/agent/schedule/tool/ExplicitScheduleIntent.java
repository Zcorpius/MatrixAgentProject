package com.matrix.agent.schedule.tool;

import static com.matrix.agent.api.schedule.ScheduleCodes.AFTER_DELAY;
import static com.matrix.agent.api.schedule.ScheduleCodes.DAILY;
import static com.matrix.agent.api.schedule.ScheduleCodes.ONCE;
import static com.matrix.agent.api.schedule.ScheduleCodes.WEEKLY;

import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.api.schedule.ScheduleTiming;
import com.matrix.agent.schedule.domain.ClockSample;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks literal user constraints before a model-authored plan may reach the store. */
final class ExplicitScheduleIntent {
    private record RequestedZone(ZoneId id, boolean followsDevice) { }
    private static final Pattern OFFSET_DATE_TIME = Pattern.compile(
            "(?<![\\d-])(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?(?:Z|[+-]\\d{2}:\\d{2}))(?!\\d)");
    private static final Pattern RELATIVE_DELAY = Pattern.compile(
            "(?<![\\d一二两三四五六七八九十百])([0-9]{1,6}|[一二两三四五六七八九十百]{1,6})\\s*(分钟|小时|天)(?:之?后|以后)");
    private static final Pattern CLOCK_TIME = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern CHINESE_CLOCK_TIME = Pattern.compile("(?<![一二三四五六七八九十百\\d])([一二两三四五六七八九十]{1,3}|[01]?\\d|2[0-3])点(?:(半)|([一二两三四五六七八九十]{1,3}|[0-5]?\\d)分?)?");
    private static final Pattern WEEKDAY = Pattern.compile("每周([一二三四五六日天])");
    private static final Pattern IANA_ZONE = Pattern.compile("([A-Za-z_]+/[A-Za-z_]+(?:/[A-Za-z_]+)?)时区");
    private static final Pattern UTC_ZONE = Pattern.compile("(?i)(?<![A-Za-z])UTC时区");
    private static final Pattern SIMPLE_SUBJECT = Pattern.compile("提醒我([\\p{IsHan}]{1,8})(?:[，。！？,.!?]|$)");
    private static final Pattern NO_CREATION = Pattern.compile(
            "仅预览|只预览|不要保存|无需保存|不要创建|不想创建|不创建|别创建|不要安排|不用提醒我");
    private static final Pattern TIME_UNDECIDED = Pattern.compile("还没决定时间|没决定时间|时间未定|时间还没定");

    private ExplicitScheduleIntent() { }

    static void verify(String user, ScheduleSpec proposed, ClockSample clock, boolean creates) {
        if (creates && (NO_CREATION.matcher(user).find() || TIME_UNDECIDED.matcher(user).find())) {
            throw new IllegalArgumentException("当前用户未授权创建确定时间的计划");
        }
        ScheduleTiming timing = proposed.timing;
        verifyZone(user, proposed, clock.deviceZone());
        verifyLiteralTime(user, timing);
        if (creates) verifySubject(user, proposed);
    }

    private static void verifyZone(String user, ScheduleSpec proposed, ZoneId deviceZone) {
        ScheduleTiming timing = proposed.timing;
        RequestedZone requested = statedZone(user, deviceZone);
        if (com.matrix.agent.schedule.workflow.WeatherWorkflow.ID.equals(proposed.action.templateId)) {
            var parameters = com.matrix.agent.schedule.domain.ScheduleCodec.arguments(proposed.action.parametersJson);
            if ("CURRENT_AT_TRIGGER".equals(parameters.get("mode")) && !user.contains("时区")
                    && !user.contains("北京时间") && !user.contains("上海时间")) {
                requested = new RequestedZone(deviceZone, true);
            } else if ("FIXED_CITY".equals(parameters.get("mode")) && !user.contains("时区")
                    && !user.contains("北京时间") && !user.contains("上海时间")) {
                try { requested = new RequestedZone(ZoneId.of(String.valueOf(parameters.get("fixedCityZone"))), false); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException("固定城市时区无效"); }
            }
        }
        ZoneId actual;
        try { actual = ZoneId.of(timing.zoneId); }
        catch (DateTimeException invalid) { throw new IllegalArgumentException("计划时区无效", invalid); }
        if (!actual.equals(requested.id()) || timing.followDeviceZone != requested.followsDevice()) {
            throw new IllegalArgumentException("计划时区与用户要求不一致");
        }
    }

    private static RequestedZone statedZone(String user, ZoneId deviceZone) {
        if (user.contains("跟随设备时区") || user.contains("跟随本地时区")) {
            return new RequestedZone(deviceZone, true);
        }
        Matcher iana = IANA_ZONE.matcher(user);
        if (iana.find()) {
            try { return new RequestedZone(ZoneId.of(iana.group(1)), false); }
            catch (DateTimeException invalid) { throw new IllegalArgumentException("用户指定的时区无效", invalid); }
        }
        if (UTC_ZONE.matcher(user).find()) return new RequestedZone(ZoneId.of("UTC"), false);
        if (user.contains("上海时间") || user.contains("北京时间")) {
            return new RequestedZone(ZoneId.of("Asia/Shanghai"), false);
        }
        if (user.contains("时区")) throw new IllegalArgumentException("请使用明确的 IANA 时区");
        // A moving-city weather brief is scheduled in the device's local time; the city zone
        // is only used to select the forecast's local date after location is resolved.
        return new RequestedZone(deviceZone, false);
    }

    private static void verifyLiteralTime(String user, ScheduleTiming timing) {
        Matcher absolute = OFFSET_DATE_TIME.matcher(user);
        if (absolute.find()) {
            long at;
            try { at = OffsetDateTime.parse(absolute.group(1)).toInstant().toEpochMilli(); }
            catch (DateTimeException invalid) { throw new IllegalArgumentException("用户指定的绝对时间无效", invalid); }
            if (absolute.find()) throw new IllegalArgumentException("请明确唯一的计划时刻");
            require(timing.kind == ONCE && timing.atMillis == at, "计划时刻与用户要求不一致");
            return;
        }
        Matcher delay = RELATIVE_DELAY.matcher(user);
        if (delay.find()) {
            long amount = numeral(delay.group(1));
            long minutes = switch (delay.group(2)) {
                case "分钟" -> amount;
                case "小时" -> Math.multiplyExact(amount, 60);
                case "天" -> Math.multiplyExact(amount, 24 * 60);
                default -> throw new IllegalStateException("unrecognized delay unit");
            };
            if (delay.find()) throw new IllegalArgumentException("请明确唯一的相对延迟");
            require(timing.kind == AFTER_DELAY && timing.delayMillis == Duration.ofMinutes(minutes).toMillis(),
                    "计划延迟与用户要求不一致");
            return;
        }
        if (user.contains("每周") || user.contains("每天")) {
            Matcher time = CLOCK_TIME.matcher(user);
            Matcher chinese = CHINESE_CLOCK_TIME.matcher(user);
            boolean numeric = time.find(), named = chinese.find();
            if (!numeric && !named) throw new IllegalArgumentException("请明确计划的具体钟点");
            String numericHour = numeric ? time.group(1) : "", numericMinute = numeric ? time.group(2) : "";
            String namedHour = named ? chinese.group(1) : "", half = named ? chinese.group(2) : null;
            String namedMinute = named ? chinese.group(3) : null;
            if (numeric && named || numeric && time.find() || named && chinese.find()
                    || user.matches("(?s).*(左右|大概|约摸|差不多).*")) throw new IllegalArgumentException("请明确唯一且精确的计划钟点");
            int hour = numeric ? Integer.parseInt(numericHour) : (int) numeral(namedHour);
            int minute = numeric ? Integer.parseInt(numericMinute) : half != null ? 30
                    : namedMinute == null ? 0 : (int) numeral(namedMinute);
            if (user.contains("下午") || user.contains("晚上")) { if (hour < 12) hour += 12; }
            if (user.contains("凌晨") && hour == 12) hour = 0;
            String localTime = LocalTime.of(hour, minute).toString();
            require(localTime.equals(timing.localTime), "计划钟点与用户要求不一致");
            if (user.contains("每周")) {
                Matcher weekday = WEEKDAY.matcher(user);
                if (!weekday.find()) throw new IllegalArgumentException("请明确计划的星期");
                String literalDay = weekday.group(1);
                int end = weekday.end();
                if ((end < user.length() && "至到、和及,-".indexOf(user.charAt(end)) >= 0)
                        || weekday.find()) {
                    throw new IllegalArgumentException("多个星期的计划请在任务中心明确设置");
                }
                int day = switch (literalDay) {
                    case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4;
                    case "五" -> 5; case "六" -> 6; case "日", "天" -> 7;
                    default -> throw new IllegalStateException("unrecognized weekday");
                };
                require(timing.kind == WEEKLY && timing.weekdaysMask == 1 << (day - 1),
                        "计划星期与用户要求不一致");
            } else require(timing.kind == DAILY, "计划周期与用户要求不一致");
        }
    }

    private static void verifySubject(String user, ScheduleSpec proposed) {
        Matcher subject = SIMPLE_SUBJECT.matcher(user);
        if (subject.find()) {
            String literal = subject.group(1);
            if (!literal.contains("和") && !literal.contains("或")
                    && !literal.matches(".*(今天|明天|后天|早上|上午|中午|下午|晚上|下次|提前).*")
                    && !proposed.action.text.contains(literal)) {
                throw new IllegalArgumentException("提醒内容与用户要求不一致");
            }
        }
    }

    private static long numeral(String value) {
        if (Character.isDigit(value.charAt(0))) return Long.parseLong(value);
        long total = 0, digit = 0;
        for (int i = 0; i < value.length(); i++) {
            char symbol = value.charAt(i);
            if (symbol == '十' || symbol == '百') {
                total += (digit == 0 ? 1 : digit) * (symbol == '十' ? 10 : 100);
                digit = 0;
            } else {
                digit = switch (symbol) {
                    case '一' -> 1; case '二', '两' -> 2; case '三' -> 3;
                    case '四' -> 4; case '五' -> 5; case '六' -> 6;
                    case '七' -> 7; case '八' -> 8; case '九' -> 9;
                    default -> throw new IllegalArgumentException("无法识别相对时间");
                };
            }
        }
        return total + digit;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
