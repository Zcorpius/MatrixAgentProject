package com.matrix.agent.data.audit;

/**
 * audit_event 增量事件 type 常量。
 *
 * <p>此前 audit_event 表仅有 TERMINAL type(实际无写入路径);
 * 接入 5 个增量事件类型,AgentEngine 在 Loop 关键节点写入:
 * <ul>
 *   <li>{@link #PRE_TOOL}——Tool 执行前(snapshot 写入 trajectory 前);</li>
 *   <li>{@link #POST_TOOL}——Tool 执行后(snapshot 写入 trajectory 前);</li>
 *   <li>{@link #POLICY}——PolicyEngine.reject 时(CAPABILITY / PARAMETER);</li>
 *   <li>{@link #STEER}——SteerMailbox.drain 拿到 FORCE_TOOL / DEFER 时;</li>
 *   <li>{@link #STEER_DROPPED_STALE}——epoch gate drop 过期 steer 时(仅声明常量);</li>
 *   <li>{@link #TERMINAL}——已声明,AgentOutcome.persist 同步写入(未改动)。</li>
 * </ul>
 *
 * <p>这些常量与 {@link com.matrix.agent.data.db.AuditEventEntity#type} 字段值一一对应,
 * 改名 / 删除需配套 Room Migration(type 列是 TEXT,值变无需 migration,但历史数据不可读)。
 */
public final class AuditEventTypes {
    public static final String PRE_TOOL = "PRE_TOOL";
    public static final String POST_TOOL = "POST_TOOL";
    public static final String POLICY = "POLICY";
    public static final String STEER = "STEER";
    public static final String STEER_DROPPED_STALE = "STEER_DROPPED_STALE";
    public static final String TERMINAL = "TERMINAL";

    private AuditEventTypes() {}
}
