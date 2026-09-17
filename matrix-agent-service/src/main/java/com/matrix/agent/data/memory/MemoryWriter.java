package com.matrix.agent.data.memory;

import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.AgentOutcome;

/**
 * Memory 写入路径——Episodic 自动 + Semantic 显式。
 *
 * <p>预建 {@code session_history} / {@code memory_record} 表后,接入了
 * Episodic / Semantic 召回(只读),但**生产路径无任何写入**——召回测试通过是因为
 * fake DAO 测试期塞数据,真实 APK 两表永远空。本接口是写入入口。
 *
 * <p><b>写入策略</b>(用户选择"Episodic 全自动,Semantic 仅显式"):
 * <ul>
 *   <li>{@link #writeEpisodicOnTerminal(AgentRequest, AgentOutcome, long)} —— AgentEngine 终态出口点
 *       自动调用,把任务终态写 session_history 表。fail-log(异常仅 Log.w,不向上传播)。</li>
 *   <li>{@link #writeSemantic(String, String, String, String, double, String, long)} —— 仅由
 *       {@code memory.semantic.save} capability handler 调用,用户显式要求长期记住的
 *       事实/知识(如"我对花生过敏")。返回写入是否成功(供 handler 转 ToolResult)。</li>
 *   <li>{@link #readSemantic(String, String, String)} —— {@code memory.semantic.get} capability
 *       handler 调用,精确 key 查询。null 表示无记录。</li>
 * </ul>
 *
 * <p><b>epoch 原子性</b>:写入接口新增 {@code requestEpoch} 参数。实现
 * 必须在**同一 Room 事务**内"读 __system__ epoch 行 + 比较 + insert/upsert",否则仍有
 * check-then-act race(clearUserData 在另一线程 bump epoch + 清两表,Java 内存比较挡不住)。
 * {@code requestEpoch != currentEpoch} 时事务内 return 不写(fail-log 模式)。
 *
 * <p><b>fail-log 语义</b>:Memory 写入失败仅 Log.w + 计数,不影响主路径(audit fail-open 不变)。
 * database=null 时退 {@link #NOOP},与 NoopAuditRepository 同模式。
 */
public interface MemoryWriter {

    /**
     * Episodic 自动写入:AgentEngine 终态出口点调用。
     *
     * <p>实现必须 fail-log(仅 Log.w + 计数,不向上传播)——保证主任务路径不被 Memory 拖累。
     * 幂等:同 (userId, zone, sessionId, startedAtMillis) 主键重复时 REPLACE。
     *
     * @param requestEpoch 任务入口捕获的 epoch(由 AgentRequest.getEpoch() 透传),
     *     与 clearUserDataAndBump 自增后的 currentEpoch 不匹配时事务内 return 不写
     */
    void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch);

    /**
     * Semantic 显式写入:仅由 save_semantic capability handler 调用。
     *
     * @param requestEpoch 任务入口捕获的 epoch,与 currentEpoch 不匹配时事务内 return 不写
     * @return 写入是否成功(供 handler 转 ToolResult)
     */
    boolean writeSemantic(String userId, String zone, String key, String value,
            double score, String sourceSessionId, long requestEpoch);

    /**
     * Semantic 显式读取:save_semantic.get / 模型查询路径调用。
     *
     * @return value 或 null(无记录)
     */
    String readSemantic(String userId, String zone, String key);

    /** Singleton NOOP,database=null 时使用,与 NoopAuditRepository.INSTANCE 同模式。 */
    MemoryWriter NOOP = new MemoryWriter() {
        @Override
        public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
            // Noop:database=null 时无持久化层,与 auditRepository fail-open 语义一致。
        }

        @Override
        public boolean writeSemantic(String userId, String zone, String key, String value,
                double score, String sourceSessionId, long requestEpoch) {
            return false;
        }

        @Override
        public String readSemantic(String userId, String zone, String key) {
            return null;
        }
    };
}
