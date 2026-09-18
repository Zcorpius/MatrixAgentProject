package com.matrix.agent.contract.schema;

/**
 * Schema 投影失败——目标 provider 不支持当前 schema 构造。
 *
 * <p>继承 {@link RuntimeException}——schema 投影失败属"开发者配置错误"
 * (给 OpenAI strict 配了 oneOf / 复杂 anyOf 等),应在 build / 调用时立即失败。
 */
public class SchemaProjectionException extends RuntimeException {
    public SchemaProjectionException(String message) {
        super(message);
    }
}
