package com.matrix.agent.task.capability.schema;

/**
 * Schema 校验诊断码。
 *
 * <p>仅作内部诊断——{@link com.matrix.agent.task.policy.PolicyDecision} 仍按
 * {@code PARAMETER} 归类(模型可在剩余预算内换参数重试)。不引入新的
 * {@code RejectionType},保持二分契约(CAPABILITY 不可上诉 / PARAMETER 可重试)不变。
 *
 * <p>覆盖的失败模式:
 * <ul>
 *   <li>{@link #MISSING_REQUIRED}——required 字段缺失</li>
 *   <li>{@link #ADDITIONAL_PROPERTY}——additionalProperties=false 时模型多塞字段</li>
 *   <li>{@link #TYPE_MISMATCH}——type 不匹配(integer 收到字符串等)</li>
 *   <li>{@link #NOT_INTEGER}——integer 收到小数 / NaN / Infinity</li>
 *   <li>{@link #OUT_OF_RANGE}——minimum / maximum 越界</li>
 *   <li>{@link #EXCLUSIVE_RANGE_VIOLATION}——exclusiveMinimum / exclusiveMaximum 越界</li>
 *   <li>{@link #ENUM_VIOLATION}——enum 集合外取值</li>
 *   <li>{@link #CONST_MISMATCH}——const 不匹配</li>
 *   <li>{@link #LENGTH_VIOLATION}——minLength / maxLength 越界</li>
 *   <li>{@link #PATTERN_MISMATCH}——pattern 不匹配</li>
 *   <li>{@link #EMPTY_STRING}——空白字符串(quirk:trim 后空字符串拒绝)</li>
 *   <li>{@link #COMPOSITION_FAILED}——allOf/oneOf/anyOf 校验未通过</li>
 *   <li>{@link #NOT_NULL}——非 nullable 节点收到 null</li>
 * </ul>
 */
public enum SchemaErrorCode {
    MISSING_REQUIRED,
    ADDITIONAL_PROPERTY,
    TYPE_MISMATCH,
    NOT_INTEGER,
    OUT_OF_RANGE,
    EXCLUSIVE_RANGE_VIOLATION,
    ENUM_VIOLATION,
    CONST_MISMATCH,
    LENGTH_VIOLATION,
    PATTERN_MISMATCH,
    EMPTY_STRING,
    COMPOSITION_FAILED,
    NOT_NULL
}
