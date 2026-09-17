package com.matrix.agent.task.capability.schema;

/**
 * Schema 构建期异常——cyclic $ref、$ref 与 type 互斥失败、$defs 在非顶层 schema 上设置等。
 *
 * <p>继承 {@link RuntimeException} 而非 checked exception——schema 装配错误属于"开发者配置错误",
 * 应在 build() 阶段显式失败,不应让调用方 try-catch 掩盖问题。
 */
public class SchemaException extends RuntimeException {
    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
