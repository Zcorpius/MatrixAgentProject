package com.matrix.agent.contract.schema;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 把 {@link CanonicalSchema} 投影到目标 provider JSON Schema。
 *
 * <p>取代 {@code ModelApiClient.toOpenAiTool / toAnthropicTool} 内部两段重复
 * 的手写 JSON——所有 schema 节点统一走本 writer,降级策略集中在 {@link SchemaProjectionConfig}。
 *
 * <h3>OpenAI strict 兼容降级</h3>
 * <ul>
 *   <li>$ref——build 时已 eager inline,write 时永不出现</li>
 *   <li>required 自动补全:未显式 required 的 property 自动加入 required 数组</li>
 *   <li>additionalProperties 强制 false(所有 OBJECT)</li>
 *   <li>anyOf 仅允许简单类型并集(STRING/INTEGER/NUMBER/BOOLEAN),其他抛
 *       {@link SchemaProjectionException}</li>
 *   <li>oneOf——OpenAI strict 不支持,直接抛</li>
 *   <li>const → single-element enum</li>
 *   <li>pattern 保留(OpenAI strict 2024-08 后支持)</li>
 * </ul>
 *
 * <h3>Anthropic Full</h3>
 * 完整 JSON Schema 2020-12,无降级。$defs 在 build 后已被清空,
 * $ref 已 inline——ANTHROPIC_FULL 输出的是内联 tree。
 *
 * <p>线程安全:无状态。
 */
public final class SchemaJsonWriter {

    public static final SchemaJsonWriter INSTANCE = new SchemaJsonWriter();

    private SchemaJsonWriter() {}

    public JSONObject write(CanonicalSchema schema, SchemaProjectionConfig config)
            throws org.json.JSONException {
        if (schema == null) throw new IllegalArgumentException("schema 不能为空");
        if (config == null) throw new IllegalArgumentException("config 不能为空");
        return writeNode(schema, config);
    }

    private JSONObject writeNode(CanonicalSchema schema, SchemaProjectionConfig config)
            throws org.json.JSONException {
        // $ref 已 eager inline,递归 walk target
        if (schema.is$Ref()) {
            return writeNode(schema.get$RefTarget(), config);
        }

        JSONObject node = new JSONObject();

        // description
        if (schema.getDescription() != null && !schema.getDescription().isEmpty()) {
            node.put("description", schema.getDescription());
        }

        // type
        if (schema.getType() != null) {
            node.put("type", jsonTypeName(schema.getType()));
        }

        // const
        if (schema.getConstValue() != null) {
            if (config == SchemaProjectionConfig.OPENAI_STRICT) {
                // OpenAI strict 不支持 const → 转 single-element enum
                JSONArray single = new JSONArray();
                single.put(schema.getConstValue());
                node.put("enum", single);
            } else {
                node.put("const", schema.getConstValue());
            }
        }

        // enum(若已设 const 转 enum,enum 互斥 schema 应 build 时拒绝)
        if (!schema.getEnumValues().isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Object v : schema.getEnumValues()) arr.put(v);
            node.put("enum", arr);
        }

        // 数值约束
        if (schema.getMinimum() != null) {
            node.put("minimum", schema.getMinimum());
        }
        if (schema.getMaximum() != null) {
            node.put("maximum", schema.getMaximum());
        }
        if (schema.isExclusiveMinimum()) {
            node.put("exclusiveMinimum", true);
        }
        if (schema.isExclusiveMaximum()) {
            node.put("exclusiveMaximum", true);
        }

        // 字符串约束
        if (schema.getMinLength() != null) {
            node.put("minLength", schema.getMinLength());
        }
        if (schema.getMaxLength() != null) {
            node.put("maxLength", schema.getMaxLength());
        }
        if (schema.getPattern() != null) {
            node.put("pattern", schema.getPattern());
        }

        // 数组 items
        if (schema.getItems() != null) {
            node.put("items", writeNode(schema.getItems(), config));
        }

        // object properties / required / additionalProperties
        if (!schema.getProperties().isEmpty()) {
            JSONObject props = new JSONObject();
            for (Map.Entry<String, CanonicalSchema> entry : schema.getProperties().entrySet()) {
                props.put(entry.getKey(), writeNode(entry.getValue(), config));
            }
            node.put("properties", props);

            JSONArray required = new JSONArray();
            Set<String> requiredKeys;
            if (config == SchemaProjectionConfig.OPENAI_STRICT) {
                // OpenAI strict 强制全列——所有 property 都 required
                requiredKeys = new LinkedHashSet<>(schema.getProperties().keySet());
            } else {
                requiredKeys = new LinkedHashSet<>(schema.getRequired());
            }
            for (String r : requiredKeys) required.put(r);
            node.put("required", required);

            // additionalProperties
            if (config == SchemaProjectionConfig.OPENAI_STRICT) {
                node.put("additionalProperties", false);
            } else {
                node.put("additionalProperties", schema.isAdditionalProperties());
            }
        } else if (schema.getType() == SchemaType.OBJECT) {
            // 空 OBJECT 也要写 required + additionalProperties 保持 strict 兼容
            node.put("required", new JSONArray());
            if (config == SchemaProjectionConfig.OPENAI_STRICT) {
                node.put("additionalProperties", false);
            } else {
                node.put("additionalProperties", schema.isAdditionalProperties());
            }
        }

        // composition
        if (!schema.getAllOf().isEmpty()) {
            JSONArray arr = new JSONArray();
            for (CanonicalSchema s : schema.getAllOf()) arr.put(writeNode(s, config));
            node.put("allOf", arr);
        }
        if (!schema.getOneOf().isEmpty()) {
            if (config == SchemaProjectionConfig.OPENAI_STRICT) {
                throw new SchemaProjectionException(
                        "OpenAI strict 不支持 oneOf,改用 anyOf 或 ANTHROPIC_FULL 投影");
            }
            JSONArray arr = new JSONArray();
            for (CanonicalSchema s : schema.getOneOf()) arr.put(writeNode(s, config));
            node.put("oneOf", arr);
        }
        if (!schema.getAnyOf().isEmpty()) {
            if (config == SchemaProjectionConfig.OPENAI_STRICT) {
                for (CanonicalSchema s : schema.getAnyOf()) {
                    if (!isSimpleType(s)) {
                        throw new SchemaProjectionException(
                                "OpenAI strict anyOf 仅允许简单类型并集(STRING/INTEGER/NUMBER/BOOLEAN),"
                                        + "收到:" + s.getType());
                    }
                }
            }
            JSONArray arr = new JSONArray();
            for (CanonicalSchema s : schema.getAnyOf()) arr.put(writeNode(s, config));
            node.put("anyOf", arr);
        }

        // MatrixAgent 私有 annotation——CANONICAL_DEBUG 保留,Provider 投影剥离
        if (config == SchemaProjectionConfig.CANONICAL_DEBUG && schema.isSensitive()) {
            node.put("sensitive", true);
            if (schema.getSensitivePlaceholder() != null) {
                node.put("sensitivePlaceholder", schema.getSensitivePlaceholder());
            }
        }

        return node;
    }

    /** OpenAI strict anyOf 仅允许:STRING / INTEGER / NUMBER / BOOLEAN 简单类型并集。 */
    private static boolean isSimpleType(CanonicalSchema schema) {
        if (schema.is$Ref()) return false;
        switch (schema.getType()) {
            case STRING:
            case INTEGER:
            case NUMBER:
            case BOOLEAN:
                return true;
            default:
                return false;
        }
    }

    private static String jsonTypeName(SchemaType type) {
        switch (type) {
            case NULL: return "null";
            case BOOLEAN: return "boolean";
            case INTEGER: return "integer";
            case NUMBER: return "number";
            case STRING: return "string";
            case ARRAY: return "array";
            case OBJECT: return "object";
            default: return type.name().toLowerCase(Locale.ROOT);
        }
    }
}
