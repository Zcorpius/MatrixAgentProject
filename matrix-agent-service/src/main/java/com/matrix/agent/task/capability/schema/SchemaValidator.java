package com.matrix.agent.task.capability.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于 {@link CanonicalSchema} 的递归 walker,把
 * {@code PolicyEngine.checkStrictSchema + checkTypeAndRangeAndEnum} 完全取代。
 *
 * <p>校验顺序遵循 JSON Schema 2020-12 标准(additionalProperties → required →
 * per-property 递归);property 内部按 const → type → enum → range → length →
 * pattern → array items → object properties → composition short-circuit。
 *
 * <h3>兼容 quirks(必须保留)</h3>
 * <ul>
 *   <li><b>enum 字符串大小写不敏感匹配</b>——原 {@code PolicyEngine} 用
 *       {@code allowed.equalsIgnoreCase(sv)},沿用。integer enum 用
 *       {@code String.valueOf((int)v)} 比对(测试 {@code validateNumber} 等已固化)。</li>
 *   <li><b>String.trim().isEmpty() 拒绝</b>——原 {@code PolicyEngine} 拒空白字符串,
 *       本 validator 沿用(测试 {@code requireText} 依赖此行为)。</li>
 *   <li><b>integer 严格整数校验</b>——{@code iv != Math.rint(iv)} 拒(测试
 *       {@code nonIntegerValueIsRejectedForIntegerParam} 依赖)。</li>
 * </ul>
 *
 * <p>线程安全:无状态,可单例使用。
 */
public final class SchemaValidator {

    public static final SchemaValidator INSTANCE = new SchemaValidator();

    private SchemaValidator() {}

    /**
     * 顶层入口——把 arguments 作为 OBJECT schema 的 instance 校验。
     *
     * @param schema 顶层 OBJECT schema(CapabilityDefinition.parameterSchema)
     * @param arguments 模型输出的参数 map
     * @return 校验结果;ok() 表示通过
     */
    public ValidationResult validateArguments(CanonicalSchema schema, Map<String, Object> arguments) {
        if (schema == null) {
            throw new IllegalArgumentException("schema 不能为空");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments 不能为空");
        }
        List<SchemaError> errors = new ArrayList<>();
        walk(schema, "", arguments, errors);
        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.failure(errors);
    }

    /**
     * 递归核心。{@code path} 是 dot 路径(顶层为 ""),{@code instance} 是当前节点值。
     * short-circuit:单节点找到首个错误即返回,但 OBJECT 的不同 property 各自产生错误。
     */
    private void walk(CanonicalSchema schema, String path, Object instance, List<SchemaError> errors) {
        if (schema.is$Ref()) {
            // build 时已 eager inline,$refTarget 必非 null
            walk(schema.get$RefTarget(), path, instance, errors);
            return;
        }

        // null 处理——type=NULL 才允许 null
        if (instance == null) {
            if (schema.getType() != SchemaType.NULL) {
                errors.add(new SchemaError(SchemaErrorCode.NOT_NULL, path, "不能为 null"));
            }
            return;
        }

        // const 先于 type(JSON Schema 2020-12 顺序)
        if (schema.getConstValue() != null) {
            if (!constMatches(schema.getConstValue(), instance)) {
                errors.add(new SchemaError(SchemaErrorCode.CONST_MISMATCH, path,
                        "必须等于 " + schema.getConstValue()));
                return;
            }
        }

        // type 校验
        if (!typeMatches(schema.getType(), instance)) {
            errors.add(new SchemaError(SchemaErrorCode.TYPE_MISMATCH, path,
                    "必须是 " + schema.getType().name().toLowerCase(Locale.ROOT) + ",实际:"
                            + instance.getClass().getSimpleName()));
            return;
        }

        // type 特定校验——按顺序:type → enum → range/exclusive → length → pattern
        SchemaErrorCode typeErr = checkTypeSpecific(schema, instance, path);
        if (typeErr != null) {
            errors.add(new SchemaError(typeErr, path, typeSpecificMessage(typeErr, schema, instance)));
            // enum / range / length 错误不需要继续往下递归
            // 但 OBJECT / ARRAY 还要继续递归——本分支已 return,所以对 OBJECT/ARRAY 单独走 walk
            return;
        }

        // enum(type 通过后再 enum——integer enum 用 String.valueOf 比对)
        if (!schema.getEnumValues().isEmpty() && !enumMatches(schema, instance)) {
            errors.add(new SchemaError(SchemaErrorCode.ENUM_VIOLATION, path,
                    "枚举值不在允许集合内:" + schema.getEnumValues()));
            return;
        }

        // 按结构递归
        switch (schema.getType()) {
            case ARRAY:
                walkArray(schema, path, instance, errors);
                break;
            case OBJECT:
                walkObject(schema, path, instance, errors);
                break;
            default:
                break;
        }

        // composition——只在主 type 通过后评估
        walkComposition(schema, path, instance, errors);
    }

    private void walkObject(CanonicalSchema schema, String path, Object instance, List<SchemaError> errors) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) instance;

        // 1. additionalProperties=false 时拒绝未声明字段
        if (!schema.isAdditionalProperties()) {
            for (String key : map.keySet()) {
                if (!schema.getProperties().containsKey(key)) {
                    String p = path.isEmpty() ? key : path + "." + key;
                    errors.add(new SchemaError(SchemaErrorCode.ADDITIONAL_PROPERTY, p,
                            "未声明参数:" + key + "(strict schema 不允许 additionalProperties)"));
                }
            }
        }

        // 2. required 检查
        for (String req : schema.getRequired()) {
            if (!map.containsKey(req)) {
                String p = path.isEmpty() ? req : path + "." + req;
                errors.add(new SchemaError(SchemaErrorCode.MISSING_REQUIRED, p,
                        "缺少必需参数:" + req));
            }
        }

        // 3. 对每个 declared property 递归(若 map 提供了)
        for (Map.Entry<String, CanonicalSchema> entry : schema.getProperties().entrySet()) {
            String key = entry.getKey();
            if (!map.containsKey(key)) continue;
            String p = path.isEmpty() ? key : path + "." + key;
            walk(entry.getValue(), p, map.get(key), errors);
        }
    }

    private void walkArray(CanonicalSchema schema, String path, Object instance, List<SchemaError> errors) {
        List<?> list = (List<?>) instance;
        CanonicalSchema items = schema.getItems();
        for (int i = 0; i < list.size(); i++) {
            String p = path + "[" + i + "]";
            walk(items, p, list.get(i), errors);
        }
    }

    private void walkComposition(CanonicalSchema schema, String path, Object instance, List<SchemaError> errors) {
        if (!schema.getAllOf().isEmpty()) {
            for (CanonicalSchema sub : schema.getAllOf()) {
                List<SchemaError> tmp = new ArrayList<>();
                walk(sub, path, instance, tmp);
                if (!tmp.isEmpty()) {
                    errors.add(new SchemaError(SchemaErrorCode.COMPOSITION_FAILED, path,
                            "allOf 校验失败:" + tmp.get(0).getMessage()));
                    return;
                }
            }
        }
        if (!schema.getOneOf().isEmpty()) {
            int matched = 0;
            for (CanonicalSchema sub : schema.getOneOf()) {
                List<SchemaError> tmp = new ArrayList<>();
                walk(sub, path, instance, tmp);
                if (tmp.isEmpty()) matched++;
            }
            if (matched != 1) {
                errors.add(new SchemaError(SchemaErrorCode.COMPOSITION_FAILED, path,
                        "oneOf 必须恰好匹配 1 个分支,实际:" + matched));
            }
        }
        if (!schema.getAnyOf().isEmpty()) {
            int matched = 0;
            for (CanonicalSchema sub : schema.getAnyOf()) {
                List<SchemaError> tmp = new ArrayList<>();
                walk(sub, path, instance, tmp);
                if (tmp.isEmpty()) matched++;
            }
            if (matched == 0) {
                errors.add(new SchemaError(SchemaErrorCode.COMPOSITION_FAILED, path,
                        "anyOf 至少匹配 1 个分支,实际:0"));
            }
        }
    }

    // ============ 兼容 quirks ============

    /** type→Java 实例的匹配,integer 必须是 Number 且整数值。 */
    private static boolean typeMatches(SchemaType type, Object instance) {
        switch (type) {
            case NULL: return instance == null;
            case BOOLEAN: return instance instanceof Boolean;
            case INTEGER:
                if (!(instance instanceof Number)) return false;
                double iv = ((Number) instance).doubleValue();
                return Double.isFinite(iv) && iv == Math.rint(iv);
            case NUMBER:
                return instance instanceof Number && Double.isFinite(((Number) instance).doubleValue());
            case STRING: return instance instanceof String;
            case ARRAY: return instance instanceof List;
            case OBJECT: return instance instanceof Map;
            default: return false;
        }
    }

    /**
     * type 通过后的 type-specific 检查——range / length / pattern。
     * 返回 null 表示通过,非 null 表示错误码。
     */
    private static SchemaErrorCode checkTypeSpecific(CanonicalSchema schema, Object instance, String path) {
        switch (schema.getType()) {
            case INTEGER:
            case NUMBER: {
                double v = ((Number) instance).doubleValue();
                if (schema.getMinimum() != null) {
                    if (schema.isExclusiveMinimum() && v <= schema.getMinimum()) {
                        return SchemaErrorCode.EXCLUSIVE_RANGE_VIOLATION;
                    }
                    if (!schema.isExclusiveMinimum() && v < schema.getMinimum()) {
                        return SchemaErrorCode.OUT_OF_RANGE;
                    }
                }
                if (schema.getMaximum() != null) {
                    if (schema.isExclusiveMaximum() && v >= schema.getMaximum()) {
                        return SchemaErrorCode.EXCLUSIVE_RANGE_VIOLATION;
                    }
                    if (!schema.isExclusiveMaximum() && v > schema.getMaximum()) {
                        return SchemaErrorCode.OUT_OF_RANGE;
                    }
                }
                return null;
            }
            case STRING: {
                String s = (String) instance;
                // quirk:trim 后空字符串拒绝
                if (s.trim().isEmpty()) return SchemaErrorCode.EMPTY_STRING;
                if (schema.getMinLength() != null && s.length() < schema.getMinLength()) {
                    return SchemaErrorCode.LENGTH_VIOLATION;
                }
                if (schema.getMaxLength() != null && s.length() > schema.getMaxLength()) {
                    return SchemaErrorCode.LENGTH_VIOLATION;
                }
                if (schema.getPattern() != null) {
                    try {
                        if (!Pattern.matches(schema.getPattern(), s)) {
                            return SchemaErrorCode.PATTERN_MISMATCH;
                        }
                    } catch (java.util.regex.PatternSyntaxException e) {
                        // pattern 自身有问题——按不匹配处理,但不抛
                        return SchemaErrorCode.PATTERN_MISMATCH;
                    }
                }
                return null;
            }
            default:
                return null;
        }
    }

    /**
     * enum 比对——
     * <ul>
     *   <li>STRING 大小写不敏感匹配</li>
     *   <li>INTEGER 用 {@code String.valueOf((int)v)} 比对</li>
     *   <li>其他类型直接 equals</li>
     * </ul>
     */
    private static boolean enumMatches(CanonicalSchema schema, Object instance) {
        // quirk:INTEGER enum 用 String.valueOf((int)v) 比对——enum value 常以
        // 字符串形式存(ToolParameterDefinition.getEnumValues 是 List<String>),
        // 把 INTEGER instance 转字符串后比对
        if (schema.getType() == SchemaType.INTEGER && instance instanceof Number) {
            String repr = String.valueOf(((Number) instance).intValue());
            for (Object allowed : schema.getEnumValues()) {
                if (String.valueOf(allowed).equals(repr)) return true;
            }
            return false;
        }
        for (Object allowed : schema.getEnumValues()) {
            if (instance instanceof String && allowed instanceof String) {
                if (((String) allowed).equalsIgnoreCase((String) instance)) return true;
            } else if (instance instanceof Number && allowed instanceof Number) {
                double a = ((Number) allowed).doubleValue();
                double b = ((Number) instance).doubleValue();
                if (Double.compare(a, b) == 0) return true;
            } else {
                if (allowed != null && allowed.equals(instance)) return true;
            }
        }
        return false;
    }

    private static boolean constMatches(Object constValue, Object instance) {
        if (constValue instanceof Number && instance instanceof Number) {
            return Double.compare(
                    ((Number) constValue).doubleValue(),
                    ((Number) instance).doubleValue()) == 0;
        }
        return constValue != null && constValue.equals(instance);
    }

    private static String typeSpecificMessage(SchemaErrorCode code, CanonicalSchema schema, Object instance) {
        switch (code) {
            case OUT_OF_RANGE:
                return "数值越界:" + instance + ",合法范围 ["
                        + schema.getMinimum() + ", " + schema.getMaximum() + "]";
            case EXCLUSIVE_RANGE_VIOLATION:
                return "数值越界(exclusive):" + instance + ",合法开区间 ("
                        + schema.getMinimum() + ", " + schema.getMaximum() + ")";
            case EMPTY_STRING:
                return "字符串不能为空";
            case LENGTH_VIOLATION:
                return "字符串长度越界,合法 ["
                        + schema.getMinLength() + ", " + schema.getMaxLength() + "]";
            case PATTERN_MISMATCH:
                return "字符串不匹配 pattern:" + schema.getPattern();
            default:
                return code.name();
        }
    }
}
