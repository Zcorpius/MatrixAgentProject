package com.matrix.agent.task.capability.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * OpenAI strict 模式 schema 投影契约测试。
 *
 * <p>覆盖 OpenAI strict 兼容降级策略:
 * <ul>
 *   <li>required 自动补全——所有 property 都进 required 数组</li>
 *   <li>additionalProperties 强制 false</li>
 *   <li>const → single-element enum</li>
 *   <li>$ref build 时已 eager inline</li>
 *   <li>anyOf 仅简单类型(STRING/INTEGER/NUMBER/BOOLEAN)</li>
 *   <li>oneOf 不支持——抛 SchemaProjectionException</li>
 * </ul>
 */
public final class SchemaJsonWriterOpenAiStrictTest {

    private JSONObject project(CanonicalSchema schema) throws Exception {
        return SchemaJsonWriter.INSTANCE.write(schema, SchemaProjectionConfig.OPENAI_STRICT);
    }

    @Test
    public void requiredAutoCompletedForAllProperties() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zone", CanonicalSchema.string().build())
                .property("temperature", CanonicalSchema.integer().build())
                // 不显式 required——OpenAI strict 自动补全
                .build();
        JSONObject json = project(schema);
        JSONArray required = json.getJSONArray("required");
        assertEquals(2, required.length());
        assertTrue(jsonArrayContains(required, "zone"));
        assertTrue(jsonArrayContains(required, "temperature"));
    }

    private static boolean jsonArrayContains(JSONArray arr, String value) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            if (value.equals(arr.get(i))) return true;
        }
        return false;
    }

    @Test
    public void additionalPropertiesAlwaysFalseOnObject() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("a", CanonicalSchema.string().build())
                .additionalProperties(true)  // 显式 true 也应被强制 false
                .build();
        JSONObject json = project(schema);
        assertFalse("OPENAI_STRICT 必须强制 additionalProperties=false",
                json.getBoolean("additionalProperties"));
    }

    @Test
    public void emptyObjectStillHasAdditionalPropertiesFalse() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object().build();
        JSONObject json = project(schema);
        assertFalse(json.getBoolean("additionalProperties"));
    }

    @Test
    public void constConvertsToSingleElementEnum() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("mode", CanonicalSchema.string().constValue("eco").build())
                .build();
        JSONObject json = project(schema);
        JSONObject mode = json.getJSONObject("properties").getJSONObject("mode");
        JSONArray enumArr = mode.getJSONArray("enum");
        assertEquals(1, enumArr.length());
        assertEquals("eco", enumArr.get(0));
        assertFalse("const 应被剥离——OpenAI strict 不识别", mode.has("const"));
    }

    @Test
    public void dollarRefInlinedAtBuildTime() throws Exception {
        CanonicalSchema coord = CanonicalSchema.object()
                .property("lat", CanonicalSchema.number().build())
                .property("lng", CanonicalSchema.number().build())
                .additionalProperties(false)
                .build();
        java.util.Map<String, CanonicalSchema> defs = new java.util.LinkedHashMap<>();
        defs.put("Coord", coord);
        CanonicalSchema schema = CanonicalSchema.object()
                .property("origin", CanonicalSchema.$ref("#/$defs/Coord").$defs(defs).build())
                .build();
        JSONObject json = project(schema);
        JSONObject origin = json.getJSONObject("properties").getJSONObject("origin");
        // $ref inline 后应看到展开的 OBJECT 结构
        assertEquals("object", origin.getString("type"));
        assertTrue(origin.has("properties"));
        assertTrue(origin.getJSONObject("properties").has("lat"));
        assertTrue(origin.getJSONObject("properties").has("lng"));
        assertFalse("inline 后无 $defs", json.has("$defs"));
    }

    @Test
    public void anyOfWithSimpleTypesProjectsAsIs() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("v", CanonicalSchema.string()
                        .anyOf(CanonicalSchema.string().build(),
                                CanonicalSchema.integer().build())
                        .build())
                .build();
        JSONObject json = project(schema);
        JSONObject v = json.getJSONObject("properties").getJSONObject("v");
        JSONArray anyOf = v.getJSONArray("anyOf");
        assertEquals(2, anyOf.length());
    }

    @Test
    public void anyOfWithComplexTypesThrowsProjectionException() throws Exception {
        CanonicalSchema complex = CanonicalSchema.object()
                .property("nested", CanonicalSchema.string().build())
                .build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("v", CanonicalSchema.string()
                        .anyOf(CanonicalSchema.string().build(), complex)
                        .build())
                .build();
        try {
            project(schema);
            fail("anyOf 含复杂类型应抛 SchemaProjectionException");
        } catch (SchemaProjectionException expected) {
            assertTrue(expected.getMessage().contains("anyOf"));
        }
    }

    @Test
    public void oneOfThrowsProjectionException() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("v", CanonicalSchema.string()
                        .oneOf(CanonicalSchema.string().build())
                        .build())
                .build();
        try {
            project(schema);
            fail("oneOf 应抛 SchemaProjectionException");
        } catch (SchemaProjectionException expected) {
            assertTrue(expected.getMessage().contains("oneOf"));
        }
    }

    @Test
    public void patternAndNumericRangePreserved() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("code", CanonicalSchema.string().pattern("[A-Z]{3}").build())
                .property("n", CanonicalSchema.integer().minimum(0).maximum(10).build())
                .build();
        JSONObject json = project(schema);
        JSONObject code = json.getJSONObject("properties").getJSONObject("code");
        assertEquals("[A-Z]{3}", code.getString("pattern"));
        JSONObject n = json.getJSONObject("properties").getJSONObject("n");
        assertEquals(0, n.getDouble("minimum"), 0d);
        assertEquals(10, n.getDouble("maximum"), 0d);
    }

    @Test
    public void nestedArrayItemsProjected() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zones", CanonicalSchema.array()
                        .items(CanonicalSchema.string()
                                .enumValues("driver", "passenger").build())
                        .build())
                .build();
        JSONObject json = project(schema);
        JSONObject zones = json.getJSONObject("properties").getJSONObject("zones");
        assertEquals("array", zones.getString("type"));
        JSONObject items = zones.getJSONObject("items");
        assertEquals("string", items.getString("type"));
        JSONArray enumArr = items.getJSONArray("enum");
        assertEquals(2, enumArr.length());
    }

    @Test
    public void sensitiveAnnotationStripped() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("destination", CanonicalSchema.string()
                        .sensitive(true).sensitivePlaceholder("<dest>").build())
                .build();
        JSONObject json = project(schema);
        JSONObject destination = json.getJSONObject("properties").getJSONObject("destination");
        assertFalse("OPENAI_STRICT 应剥离 sensitive annotation",
                destination.has("sensitive"));
        assertFalse(destination.has("sensitivePlaceholder"));
    }
}
