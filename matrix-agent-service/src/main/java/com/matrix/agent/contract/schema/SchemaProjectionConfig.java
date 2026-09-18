package com.matrix.agent.contract.schema;

/**
 * Schema 投影目标 provider——决定 {@link SchemaJsonWriter} 的降级策略。
 *
 * <ul>
 *   <li>{@link #OPENAI_STRICT}——OpenAI Tool Calling strict 模式
 *       (2024-08+ GPT-4 / GPT-3.5):强制 additionalProperties=false、required 全列、
 *       $ref inline、anyOf 仅简单类型、oneOf 不支持、const → single-element enum、
 *       pattern 保留(strict 后支持)。</li>
 *   <li>{@link #ANTHROPIC_FULL}——Anthropic Messages API input_schema:完整 JSON Schema
 *       2020-12,无降级(allOf/oneOf/anyOf/$defs/const 全保留,eager inline 后无 $ref)。</li>
 *   <li>{@link #CANONICAL_DEBUG}——本地调试:输出内部 CanonicalSchema 全字段,
 *       不做降级,供 logging / 文档生成。</li>
 * </ul>
 */
public enum SchemaProjectionConfig {
    OPENAI_STRICT,
    ANTHROPIC_FULL,
    CANONICAL_DEBUG
}
