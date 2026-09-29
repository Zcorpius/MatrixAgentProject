package com.matrix.agent.launcher.presentation;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ScheduleLabels {
    private ScheduleLabels() { }
    static String time(long millis) { return millis == 0 ? "—" : DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis)); }
    static String plan(int state) { return switch (state) { case ACTIVE -> "已启用"; case PAUSED -> "已暂停"; case COMPLETED -> "已结束"; case DELETED -> "已删除"; default -> "草稿"; }; }
    static String plan(com.matrix.agent.api.schedule.ScheduleInfo plan) {
        if (plan.health == BLOCKED) return plan(plan.state) + " · 需要处理";
        if (plan.state == ACTIVE) return plan.health == ARMED ? "已安排" : "已保存，正在安排";
        return plan(plan.state);
    }
    static String reason(String code) {
        return switch (code) {
            case "NOTIFICATION_PERMISSION_DENIED", "NOTIFICATIONS_DISABLED" -> "应用通知未获允许，请在系统设置中开启";
            case "NOTIFICATION_CHANNEL_BLOCKED" -> "此类通知渠道已关闭，请在系统设置中检查";
            case "NOTIFICATION_CHANNEL_MISSING", "NOTIFICATION_CHANNEL_INITIALIZATION_FAILED", "NOTIFICATION_SERVICE_UNAVAILABLE" -> "通知服务或渠道暂不可用，尚未交付";
            case "NOTIFICATION_NOT_CONFIRMED" -> "尚未确认系统保留了通知，请检查运行记录";
            case "EXACT_ALARM_PERMISSION_DENIED" -> "精确闹钟权限未开启，计划尚未安排";
            case "AUTHORIZATION_REVOKED" -> "原授权或当前用户条件已变化，执行已停止";
            case "NETWORK_UNAVAILABLE" -> "网络不可用";
            case "SUPPRESSED_BY_POLICY" -> "播报被当前勿扰、通话或夜间策略抑制";
            case "PROCESS_INTERRUPTED_AFTER_START", "STEP_EXECUTION_UNKNOWN" -> "执行中断，外部效果尚待核验，不会自动重做";
            case "OUTSIDE_ALLOWED_WINDOW", "QUEUE_EXPIRED" -> "已超过本次允许的执行窗口";
            case "PREVIOUS_RUN_UNRESOLVED" -> "前一次运行尚未解决，已跳过重叠执行";
            case "BUDGET_OR_EXPIRY_EXHAUSTED" -> "执行预算或有效时间已用尽";
            case "REQUIRED_STEP_FAILED", "REQUIRED_DEPENDENCY_FAILED" -> "必需步骤失败，后续依赖步骤不再执行";
            case "USER_DISABLED" -> "创建计划时未选择此可选步骤";
            case "NOT_ATTEMPTED_NOTIFICATION_BLOCKED" -> "通知未获允许，未尝试改用播报";
            case "NOT_ATTEMPTED_EMPTY_RESULT" -> "没有可播报内容";
            case "SPEECH_OUTCOME_UNKNOWN" -> "播报已尝试，完成情况待核验，不自动重播";
            case "NO_TOOL_CALL" -> "";
            default -> code;
        };
    }
    static String run(int state) { return switch (state) {
        case QUEUED -> "排队中"; case RUNNING -> "执行中"; case SUCCEEDED -> "已完成";
        case PARTIAL -> "部分完成"; case FAILED -> "失败"; case CANCELLED -> "已取消";
        case EXECUTION_UNKNOWN -> "执行结果待核对"; case MISSED -> "已错过"; case SKIPPED -> "已跳过";
        case WAITING_TRIGGER -> "等待改期后的触发"; case WAITING_CONDITION -> "等待条件";
        case CANCEL_REQUESTED -> "正在停止"; case WAITING_DEPENDENCY -> "等待前序步骤";
        case RETRY_WAIT -> "等待重试"; default -> "未知状态（" + state + "）";
    }; }
    static String delivery(int status) { return switch (status) {
        case DELIVERY_PENDING -> "等待投递"; case DELIVERED -> "已发布通知；是否响铃由系统设置控制，未核验声音";
        case DELIVERY_BLOCKED -> "投递被权限或渠道阻止"; case DELIVERY_FAILED -> "投递失败";
        case DELIVERY_PARTIAL -> "部分渠道已交付，其他渠道未完成";
        case DELIVERY_UNKNOWN -> "投递结果待核对"; case DELIVERY_NOT_REQUIRED -> "无需投递"; default -> "投递状态未知";
    }; }
}
