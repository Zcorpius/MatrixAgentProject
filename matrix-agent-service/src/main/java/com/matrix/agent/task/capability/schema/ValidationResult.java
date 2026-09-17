package com.matrix.agent.task.capability.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Schema 校验结果。不可变。
 *
 * <p>{@link #isOk()} 为 true 时 {@link #getErrors()} 必空;为 false 时 errors 至少 1 条。
 * 校验遇到第一层致命错误时即返回(short-circuit),但嵌套层级会聚合到一个结果——
 * 例如 OBJECT 校验 additionalProperties + required + per-property,失败的 property
 * 各自产生一条 error,聚合到同一 result。
 */
public final class ValidationResult {
    private final boolean ok;
    private final List<SchemaError> errors;

    private ValidationResult(boolean ok, List<SchemaError> errors) {
        this.ok = ok;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult failure(List<SchemaError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("failure 必须含至少一条 error");
        }
        return new ValidationResult(false, errors);
    }

    public static ValidationResult failure(SchemaError error) {
        if (error == null) throw new IllegalArgumentException("error 不能为空");
        List<SchemaError> single = new ArrayList<>();
        single.add(error);
        return new ValidationResult(false, single);
    }

    public boolean isOk() { return ok; }
    public List<SchemaError> getErrors() { return errors; }

    /** 第一条 error 的 code;ok 时返回 null。 */
    public SchemaErrorCode firstErrorCode() {
        return errors.isEmpty() ? null : errors.get(0).getCode();
    }

    /** 第一条 error 的 path;ok 时返回 null。 */
    public String firstErrorPath() {
        return errors.isEmpty() ? null : errors.get(0).getPath();
    }
}
