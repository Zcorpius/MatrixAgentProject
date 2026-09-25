package com.matrix.agent.data.memory;

import com.matrix.agent.identity.AgentRequest;

/**
 * Persistence boundary for automatic episodic events and explicitly saved semantic facts.
 * Operations accept a bound {@link AgentRequest}. Semantic scope is derived inside the Writer;
 * an episodic command must match the same request in every identity field.
 * Writes and deletes compare the request epoch with the database epoch in one transaction.
 */
public interface MemoryWriter {

    /**
     * Episodic 自动写入:task 侧适配器完成终态过滤与摘要构建后调用。
     *
     * <p>实现必须 fail-log(仅 Log.w + 计数,不向上传播)——保证主任务路径不被 Memory 拖累。
     * 幂等:同 (userId, zone, sessionId, startedAtMillis) 主键重复时 REPLACE。
     *
     * <p>Writer first checks that every owner/session/epoch field in the command matches
     * the bound request. 事务内 epoch gate 不变：{@code write.requestEpoch} 与
     * clearUserDataAndBump 自增后的 currentEpoch 不匹配时事务内 return 不写。
     */
    void writeEpisodic(AgentRequest request, EpisodicWrite write);

    /**
     * Semantic 显式写入:仅由 save_semantic capability handler 调用。
     *
     * @return 写入是否成功(供 handler 转 ToolResult)
     */
    boolean writeSemantic(AgentRequest request, String key, String value, double score);

    default MemoryWriteOutcome writeSemanticDetailed(AgentRequest request, String key,
            String value, double score) {
        return writeSemantic(request, key, value, score)
                ? MemoryWriteOutcome.SAVED : MemoryWriteOutcome.STORAGE_FAILURE;
    }

    /**
     * Semantic 显式读取:save_semantic.get / 模型查询路径调用。
     *
     * @return value 或 null(无记录)
     */
    String readSemantic(AgentRequest request, String key);

    /** Delete one semantic key under the same epoch gate as writes. */
    default boolean deleteSemantic(AgentRequest request, String key) {
        return deleteSemanticDetailed(request, key)
                == MemoryDeleteOutcome.DELETED;
    }

    default MemoryDeleteOutcome deleteSemanticDetailed(AgentRequest request, String key) {
        return MemoryDeleteOutcome.STORAGE_FAILURE;
    }

    /** Owner-bound exact lookup; returns validated event facts as JSON, or null. */
    default String readEpisodic(AgentRequest request, String eventId) { return null; }

    /** Deletes one owner-bound event under the same epoch transaction gate as writes. */
    default MemoryDeleteOutcome deleteEpisodic(AgentRequest request, String eventId) {
        return MemoryDeleteOutcome.STORAGE_FAILURE;
    }

    /** Singleton NOOP,database=null 时使用,与 NoopAuditRepository.INSTANCE 同模式。 */
    MemoryWriter NOOP = new MemoryWriter() {
        @Override
        public void writeEpisodic(AgentRequest request, EpisodicWrite write) {
            // Noop:database=null 时无持久化层,与 auditRepository fail-open 语义一致。
        }

        @Override
        public boolean writeSemantic(AgentRequest request, String key, String value, double score) {
            return false;
        }

        @Override
        public String readSemantic(AgentRequest request, String key) {
            return null;
        }
    };
}
