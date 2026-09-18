package com.matrix.agent.contract.schema;

/**
 * 单条 Schema 校验错误。不可变。
 *
 * <p>{@code path} 用 dot 路径(如 {@code "zone"} / {@code "items.temperature"}),
 * 便于上层把错误归因到具体参数名喂给模型重试。
 */
public final class SchemaError {
    private final SchemaErrorCode code;
    private final String path;
    private final String message;

    public SchemaError(SchemaErrorCode code, String path, String message) {
        if (code == null) throw new IllegalArgumentException("code 不能为空");
        if (path == null) throw new IllegalArgumentException("path 不能为空");
        if (message == null || message.isEmpty()) throw new IllegalArgumentException("message 不能为空");
        this.code = code;
        this.path = path;
        this.message = message;
    }

    public SchemaErrorCode getCode() { return code; }
    public String getPath() { return path; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return code + " @ " + path + ": " + message;
    }
}
