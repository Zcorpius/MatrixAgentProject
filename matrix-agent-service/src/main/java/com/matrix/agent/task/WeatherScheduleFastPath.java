package com.matrix.agent.task;

import static com.matrix.agent.api.schedule.ScheduleCodes.DRAFT;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.tool.ToolResult;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded planner for an explicit daily weather reminder; the schedule provider remains authoritative. */
final class WeatherScheduleFastPath {
    private static final Pattern NUMERIC = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern CHINESE = Pattern.compile("(?<![一二三四五六七八九十百\\d])"
            + "([一二两三四五六七八九十]{1,3}|[01]?\\d|2[0-3])点(?:(半)|([一二两三四五六七八九十]{1,3}|[0-5]?\\d)分?)?");

    private WeatherScheduleFastPath() { }

    static ToolCall plan(AgentRequest request) {
        if (!WeatherScheduleToolProjection.eligible(request) || request.getExecutionScope().automatic()) return null;
        String user = request.getInteractiveOrigin().userText();
        if (!user.contains("每天") || !user.contains("告诉我") || user.matches("(?s).*(左右|大概|约摸|差不多).*")) return null;
        Matcher numeric = NUMERIC.matcher(user), chinese = CHINESE.matcher(user);
        boolean hasNumeric = numeric.find(), hasChinese = chinese.find();
        if (hasNumeric == hasChinese) return null;
        String numericHour = hasNumeric ? numeric.group(1) : "", numericMinute = hasNumeric ? numeric.group(2) : "";
        String chineseHour = hasChinese ? chinese.group(1) : "";
        String chineseHalf = hasChinese ? chinese.group(2) : null;
        String chineseMinute = hasChinese ? chinese.group(3) : null;
        if (hasNumeric && numeric.find() || hasChinese && chinese.find()) return null;
        try {
            int hour = hasNumeric ? Integer.parseInt(numericHour) : numeral(chineseHour);
            int minute = hasNumeric ? Integer.parseInt(numericMinute)
                    : chineseHalf != null ? 30 : chineseMinute == null ? 0 : numeral(chineseMinute);
            if ((user.contains("下午") || user.contains("晚上")) && hour < 12) hour += 12;
            if (user.contains("凌晨") && hour == 12) hour = 0;
            String localTime = LocalTime.of(hour, minute).toString();
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("title", "每日天气简报");
            arguments.put("text", "当天的天气情况");
            arguments.put("timeKind", "DAILY");
            arguments.put("localTime", localTime);
            arguments.put("action", "WORKFLOW");
            arguments.put("templateId", "daily_weather_current");
            arguments.put("templateVersion", 1);
            arguments.put("weatherMode", "CURRENT_AT_TRIGGER");
            // A weather request does not silently grant location, third-party network, or speech.
            arguments.put("allowLocation", false);
            arguments.put("allowNetwork", false);
            arguments.put("speakResult", false);
            return new ToolCall("schedule.create", arguments);
        } catch (RuntimeException invalid) { return null; }
    }

    static String answer(ToolCall call, ToolResult result) {
        if (result.isSuccess()) {
            String time = String.valueOf(call.argument("localTime"));
            if (result.getObservedState().get("state") instanceof Number state && state.intValue() == DRAFT)
                return "已保存每天 " + time + " 的 Agent 天气提醒草稿，尚未启用。请在任务中心配置天气服务，分别授权当前位置和联网；若后台定位不可用，可改为固定城市。它不是系统原生闹钟。";
            return "已保存每天 " + time + " 的 Agent 天气提醒；请在任务中心核对启用状态和下一次触发时间。它不是系统原生闹钟。";
        }
        return "天气提醒尚未创建：" + result.getMessage();
    }

    private static int numeral(String value) {
        if (Character.isDigit(value.charAt(0))) return Integer.parseInt(value);
        int total = 0, digit = 0;
        for (int index = 0; index < value.length(); index++) {
            char symbol = value.charAt(index);
            if (symbol == '十') { total += (digit == 0 ? 1 : digit) * 10; digit = 0; }
            else digit = switch (symbol) {
                case '一' -> 1; case '二', '两' -> 2; case '三' -> 3; case '四' -> 4;
                case '五' -> 5; case '六' -> 6; case '七' -> 7; case '八' -> 8;
                case '九' -> 9; default -> throw new IllegalArgumentException("unsupported time");
            };
        }
        return total + digit;
    }
}
