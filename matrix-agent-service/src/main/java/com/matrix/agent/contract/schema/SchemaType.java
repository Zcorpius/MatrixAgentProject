package com.matrix.agent.contract.schema;

/**
 * JSON Schema 2020-12 类型枚举。
 *
 * <p>覆盖 MatrixAgent 需要的 7 种基本类型:
 * <ul>
 *   <li>{@link #NULL} / {@link #BOOLEAN} / {@link #INTEGER} / {@link #NUMBER} / {@link #STRING}
 *       —— 基础标量</li>
 *   <li>{@link #ARRAY} —— 配合 {@code CanonicalSchema.items}</li>
 *   <li>{@link #OBJECT} —— 配合 {@code properties} / {@code required} / {@code additionalProperties}</li>
 * </ul>
 *
 * <p>{@code const} 不进 Type(作为 {@link CanonicalSchema#getConstValue()} 独立字段);
 * {@code allOf / oneOf / anyOf} 不作为 Type(作为组合关键字独立字段);
 * {@code $ref} 不作为 Type(build 时已 inline)。
 *
 * <p>{@link #INTEGER} 与 {@link #NUMBER} 分开保留,以便 OpenAI strict 模式严格区分。
 */
public enum SchemaType {
    NULL,
    BOOLEAN,
    INTEGER,
    NUMBER,
    STRING,
    ARRAY,
    OBJECT
}
