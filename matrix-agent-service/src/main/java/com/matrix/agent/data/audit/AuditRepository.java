package com.matrix.agent.data.audit;

import java.util.List;

/**
 * Audit 持久化入口——AgentEngine 5 个出口点统一调用。
 *
 * <p>实现策略:
 * <ul>
 *   <li>{@link RoomAuditRepository} —— APK 主路径,同步阻塞 + fail-open(失败仅 log)。</li>
 *   <li>{@link NoopAuditRepository} —— JVM 单测 / 装配失败兜底,空实现。</li>
 * </ul>
 *
 * <p>仅 persist + query;加 incremental(iteration) + WAL/batch。
 *
 * <p><b>修复</b>:query 路径强制传入访问域 (userId, zone)。
 *
 * <p><b>修复</b>:{@code queryByRequest(String requestId)} 改签名为
 * {@link #queryByRequest(String, String, String)}——UUID 全局唯一不能替代权限校验,
 * 任何 caller 都不能凭枚举 requestId 越权读他人审计。SQL WHERE 强制 userId + zone
 * 双重过滤,跨用户 / 跨 zone 查询直接返回 null。
 */
public interface AuditRepository {

    /**
     * 持久化已由 task 侧投影完成的一条终态审计命令。
     *
     * <p>同步阻塞单条 insert;失败必须 fail-open(仅 log,不抛)——
     * 保证 Audit 不影响主任务路径。引入 WAL + 重试队列后改 fail-closed。
     */
    void persist(AuditOutcomeEntry entry);

    /**
     * 按 (userId, zone, requestId) 查询单条记录——SQL WHERE 强制访问域,
     * 不匹配返回 null。UUID 唯一不能替代权限校验。
     */
    AuditRecord queryByRequest(String userId, String zone, String requestId);

    /**
     * 按 (userId, zone, sessionId) 查询最近 N 条记录(按 startedMs 倒序)。
     *
     * <p>接口层强制访问域——调用方必须显式传入 userId + zone,
     * 不允许只传 sessionId。TrajectoryDao SQL 同步加 WHERE userId/zone 过滤。
     */
    List<AuditRecord> queryBySession(String userId, String zone, String sessionId, int limit);

    /**
     * 按 (userId, zone) 批量清理 Audit 落库数据,
     * 返回结构化结果 {@link ClearOutcome}。
     *
     * <p>默认返回 {@link ClearOutcome#notApplicable()},兼容 {@link NoopAuditRepository} /
     * JVM 测试 fake / 旧版单参 {@link RoomAuditRepository}(无 database 引用)路径。
     *
     * <p>{@link RoomAuditRepository} 5 参构造器(持有 {@code MatrixDatabase} + 多个 DAO)覆写
     * 本方法,在 {@code database.runInTransaction} 内跨 trajectory / session_history /
     * memory_record 三表原子删除。AuditEventEntity 无写入路径 + 无 userId 列,不参与。
     *
     * <p><b>失败语义变更</b>:初版让本方法 try/catch
     * 吞所有异常,UI 永远显示"已清空"——指出"主流程已删 / Audit 可能仍在"的语义错位。
     * 修正:本方法仍 try/catch 不抛(主流程已成功清空偏好 + 会话,不让 audit 失败回滚主流程),
     * 但失败信息封装进 {@link ClearOutcome#partialFailure(int, int, String)} /
     * {@link ClearOutcome#failure(String)},让 Repository + ViewModel 据此选择
     * "已清空" vs "上下文已清,审计删除失败,请稍后重试点击清空"文案。
     */
    default ClearOutcome clearByUserZone(String userId, String zone) {
        return ClearOutcome.notApplicable();
    }
}
