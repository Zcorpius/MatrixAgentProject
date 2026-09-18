package com.matrix.agent.contract.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * SchemaValidator 单元测试。
 *
 * <p>覆盖所有 {@link SchemaErrorCode}、嵌套 array/object、composition、
 * 以及旧版 quirks 兼容性(必须保留):
 * <ol>
 *   <li>enum 字符串大小写不敏感匹配</li>
 *   <li>integer enum 用 {@code String.valueOf((int)v)} 比对</li>
 *   <li>String.trim().isEmpty() 拒绝</li>
 * </ol>
 */
public final class SchemaValidatorTest {

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static ValidationResult validate(CanonicalSchema schema, Map<String, Object> args) {
        return SchemaValidator.INSTANCE.validateArguments(schema, args);
    }

    @Test
    public void validArgumentsReturnOk() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zone", CanonicalSchema.string()
                        .enumValues("driver", "passenger").build())
                .property("temperature", CanonicalSchema.integer()
                        .minimum(16).maximum(30).build())
                .required("zone", "temperature")
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("zone", "driver", "temperature", 24));
        assertTrue("合法调用应通过:" + r.getErrors(), r.isOk());
        assertNull(r.firstErrorCode());
    }

    @Test
    public void missingRequiredFieldReturnsMissingRequired() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("temperature", CanonicalSchema.integer().build())
                .required("temperature")
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args());
        assertFalse(r.isOk());
        assertEquals(SchemaErrorCode.MISSING_REQUIRED, r.firstErrorCode());
        assertEquals("temperature", r.firstErrorPath());
        assertTrue(r.getErrors().get(0).getMessage().contains("temperature"));
    }

    @Test
    public void additionalPropertyReturnsAdditionalProperty() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("temperature", CanonicalSchema.integer().build())
                .required("temperature")
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("temperature", 24, "priority", "high"));
        assertFalse(r.isOk());
        assertEquals(SchemaErrorCode.ADDITIONAL_PROPERTY, r.firstErrorCode());
        assertEquals("priority", r.firstErrorPath());
        assertTrue(r.getErrors().get(0).getMessage().contains("priority"));
    }

    @Test
    public void typeMismatchIntegerReturnsTypeMismatch() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("temperature", CanonicalSchema.integer().build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("temperature", "24"));
        assertFalse(r.isOk());
        assertEquals(SchemaErrorCode.TYPE_MISMATCH, r.firstErrorCode());
        assertEquals("temperature", r.firstErrorPath());
    }

    @Test
    public void nonIntegerValueIsRejectedForIntegerParam() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("temperature", CanonicalSchema.integer().build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("temperature", 24.5));
        assertFalse(r.isOk());
        // 24.5 不是整数值 → type 校验失败(TYPE_MISMATCH),历史行为
        assertEquals(SchemaErrorCode.TYPE_MISMATCH, r.firstErrorCode());
    }

    @Test
    public void outOfRangeIntegerReturnsOutOfRange() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("temperature", CanonicalSchema.integer()
                        .minimum(16).maximum(30).build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("temperature", 31));
        assertFalse(r.isOk());
        assertEquals(SchemaErrorCode.OUT_OF_RANGE, r.firstErrorCode());
    }

    @Test
    public void exclusiveRangeViolationReturnsExclusiveRange() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("n", CanonicalSchema.integer()
                        .minimum(0).maximum(10)
                        .exclusiveMinimum(true).exclusiveMaximum(true).build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("n", 0));  // 0 排除
        assertEquals(SchemaErrorCode.EXCLUSIVE_RANGE_VIOLATION, r.firstErrorCode());
    }

    @Test
    public void enumViolationReturnsEnumViolation() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zone", CanonicalSchema.string()
                        .enumValues("driver", "passenger").build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("zone", "rear_left"));
        assertEquals(SchemaErrorCode.ENUM_VIOLATION, r.firstErrorCode());
    }

    /** 旧版 quirk:STRING enum 大小写不敏感匹配。 */
    @Test
    public void enumStringMatchIsCaseInsensitive() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zone", CanonicalSchema.string()
                        .enumValues("Driver", "Passenger").build())
                .additionalProperties(false)
                .build();
        // "driver" 小写应匹配 "Driver"——equalsIgnoreCase 行为
        ValidationResult r = validate(schema, args("zone", "driver"));
        assertTrue("enum 大小写不敏感匹配应通过:" + r.getErrors(), r.isOk());
    }

    /** 旧版 quirk:INTEGER enum 用 String.valueOf((int)v) 比对。 */
    @Test
    public void integerEnumUsesStringValueOfComparison() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("level", CanonicalSchema.integer()
                        .enumValues("0", "1", "2").build())
                .additionalProperties(false)
                .build();
        // 传 Integer 1,enum 是字符串 "1"——用 String.valueOf((int)v) 比对
        ValidationResult r = validate(schema, args("level", 1));
        assertTrue("integer enum String.valueOf 比对应通过:" + r.getErrors(), r.isOk());
    }

    @Test
    public void constMismatchReturnsConstMismatch() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("mode", CanonicalSchema.string().constValue("eco").build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("mode", "sport"));
        assertEquals(SchemaErrorCode.CONST_MISMATCH, r.firstErrorCode());
    }

    @Test
    public void lengthViolationReturnsLengthViolation() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("name", CanonicalSchema.string()
                        .minLength(2).maxLength(5).build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("name", "a"));
        assertEquals(SchemaErrorCode.LENGTH_VIOLATION, r.firstErrorCode());

        ValidationResult r2 = validate(schema, args("name", "abcdefgh"));
        assertEquals(SchemaErrorCode.LENGTH_VIOLATION, r2.firstErrorCode());
    }

    @Test
    public void patternMismatchReturnsPatternMismatch() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("code", CanonicalSchema.string().pattern("[A-Z]{3}").build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("code", "abc"));
        assertEquals(SchemaErrorCode.PATTERN_MISMATCH, r.firstErrorCode());
    }

    @Test
    public void emptyStringReturnsEmptyString() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("destination", CanonicalSchema.string().build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("destination", "   "));
        // 旧版 quirk:trim().isEmpty() 拒绝
        assertEquals(SchemaErrorCode.EMPTY_STRING, r.firstErrorCode());
    }

    @Test
    public void nestedArrayItemsRecursive() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zones", CanonicalSchema.array()
                        .items(CanonicalSchema.string().enumValues("driver", "passenger").build())
                        .build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("zones", Arrays.asList("driver", "rear_left")));
        assertFalse(r.isOk());
        assertEquals(SchemaErrorCode.ENUM_VIOLATION, r.firstErrorCode());
        assertEquals("zones[1]", r.firstErrorPath());  // 嵌套 path
    }

    @Test
    public void nestedObjectPropertiesRecursive() {
        CanonicalSchema address = CanonicalSchema.object()
                .property("city", CanonicalSchema.string().build())
                .property("zip", CanonicalSchema.string().pattern("\\d{6}").build())
                .required("city", "zip")
                .additionalProperties(false)
                .build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("destination", address)
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("destination", args("city", "北京", "zip", "abc")));
        assertFalse(r.isOk());
        assertEquals(SchemaErrorCode.PATTERN_MISMATCH, r.firstErrorCode());
        assertEquals("destination.zip", r.firstErrorPath());  // 嵌套 path
    }

    @Test
    public void compositionAllOfMustAllPass() {
        CanonicalSchema nonNeg = CanonicalSchema.integer().minimum(0).build();
        CanonicalSchema small = CanonicalSchema.integer().maximum(10).build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("x", CanonicalSchema.integer().allOf(nonNeg, small).build())
                .additionalProperties(false)
                .build();
        assertTrue(validate(schema, args("x", 5)).isOk());
        ValidationResult r = validate(schema, args("x", 11));
        assertEquals(SchemaErrorCode.COMPOSITION_FAILED, r.firstErrorCode());
    }

    @Test
    public void compositionOneOfMustMatchExactlyOne() {
        CanonicalSchema negative = CanonicalSchema.integer().maximum(-1).build();
        CanonicalSchema positive = CanonicalSchema.integer().minimum(1).build();
        CanonicalSchema zeroConst = CanonicalSchema.integer().constValue(0).build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("x", CanonicalSchema.integer()
                        .oneOf(negative, positive, zeroConst).build())
                .additionalProperties(false)
                .build();
        assertTrue(validate(schema, args("x", 5)).isOk());
    }

    @Test
    public void compositionAnyOfAtLeastOneMatch() {
        CanonicalSchema small = CanonicalSchema.integer().maximum(5).build();
        CanonicalSchema large = CanonicalSchema.integer().minimum(100).build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("x", CanonicalSchema.integer()
                        .anyOf(small, large).build())
                .additionalProperties(false)
                .build();
        assertTrue(validate(schema, args("x", 3)).isOk());
        assertTrue(validate(schema, args("x", 200)).isOk());
        ValidationResult r = validate(schema, args("x", 50));
        assertEquals(SchemaErrorCode.COMPOSITION_FAILED, r.firstErrorCode());
    }

    @Test
    public void nullValueForNotNullableReturnsNotNull() {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("name", CanonicalSchema.string().build())
                .additionalProperties(false)
                .build();
        ValidationResult r = validate(schema, args("name", null));
        assertEquals(SchemaErrorCode.NOT_NULL, r.firstErrorCode());
    }

    @Test
    public void dollarRefIsEagerInlinedAtBuildTime() {
        CanonicalSchema coord = CanonicalSchema.object()
                .property("lat", CanonicalSchema.number().build())
                .property("lng", CanonicalSchema.number().build())
                .required("lat", "lng")
                .additionalProperties(false)
                .build();
        Map<String, CanonicalSchema> defs = new LinkedHashMap<>();
        defs.put("Coord", coord);
        CanonicalSchema schema = CanonicalSchema.object()
                .property("origin", CanonicalSchema.$ref("#/$defs/Coord").$defs(defs).build())
                .additionalProperties(false)
                .build();
        ValidationResult ok = validate(schema, args("origin",
                args("lat", 39.9, "lng", 116.4)));
        assertTrue(ok.isOk());
        ValidationResult missing = validate(schema, args("origin", args("lat", 39.9)));  // 缺 lng
        assertEquals(SchemaErrorCode.MISSING_REQUIRED, missing.firstErrorCode());
        assertEquals("origin.lng", missing.firstErrorPath());
    }
}
