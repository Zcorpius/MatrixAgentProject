package com.matrix.agent.task;

/**
 * Stale Steer 处理器接口——封装 drop + audit 逻辑。
 *
 * <p>SteerMailbox 看到 stamped.epoch &lt; currentEpoch 的 Steer 时调用
 * {@link #onStaleSteerDropped};AppContainer 注入真实实现(走 AuditEventRecorder
 * 写 STEER_DROPPED_STALE 事件到 audit_event 表),单测注入 fake 验证调用路径。
 *
 * <p><b>PII 保护</b>:Stale Steer 的 payload **不**记入 audit_event 表——
 * REPROMPT payload 可能含用户输入(目的地 / 联系人 / 偏好),有 PII 风险。
 * 仅记 type + payloadChars + stampedEpoch + currentEpoch。
 */
public interface StaleSteerHandler {

    /**
     * SteerMailbox drop stale Steer 时回调。
     *
     * @param sessionId 会话 ID
     * @param steer 被丢弃的 Steer(payload 不入 audit)
     * @param stampedEpoch Steer 入队时的 epoch
     * @param currentEpoch 当前 SteerMailbox epoch(clearUserData 推进后的值)
     */
    void onStaleSteerDropped(String sessionId, Steer steer, long stampedEpoch, long currentEpoch);
}
