package com.matrix.agent.task.capability.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Anthropic Full schema 投影契约测试。
 *
 * <p>ANTHROPIC_FULL 不做降级,完整保留 JSON Schema 2020-12 语义:
 * <ul>
 *   <li>allOf / oneOf / anyOf 原样输出</li>
 *   <li>const 作为 const 字段保留(不转 enum)</li>
 *   <li>pattern / minLength / maxLength 保留</li>
 *   <li>additionalProperties 跟随 schema 自身声明(默认 false)</li>
 *   <li>required 显式声明(不自动补全)</li>
 * </ul>
 */
public final class SchemaJsonWriterAnthropicTest {

    private JSONObject project(CanonicalSchema schema) throws Exception {
        return SchemaJsonWriter.INSTANCE.write(schema, SchemaProjectionConfig.ANTHROPIC_FULL);
    }

    @Test
    public void fullSchemaPreservesAllOfOneOfAnyOf() throws Exception {
        CanonicalSchema a = CanonicalSchema.object()
                .property("x", CanonicalSchema.string().build())
                .build();
        CanonicalSchema b = CanonicalSchema.object()
                .property("y", CanonicalSchema.integer().build())
                .build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("obj", CanonicalSchema.object()
                        .allOf(a)
                        .oneOf(a, b)
                        .anyOf(a, b)
                        .build())
                .build();
        JSONObject json = project(schema);
        JSONObject obj = json.getJSONObject("properties").getJSONObject("obj");
        assertTrue("allOf 应保留", obj.has("allOf"));
        assertEquals(1, obj.getJSONArray("allOf").length());
        assertTrue("oneOf 应保留", obj.has("oneOf"));
        assertEquals(2, obj.getJSONArray("oneOf").length());
        assertTrue("anyOf 应保留", obj.has("anyOf"));
        assertEquals(2, obj.getJSONArray("anyOf").length());
    }

    @Test
    public void constPreservedAsConstField() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("mode", CanonicalSchema.string().constValue("eco").build())
                .build();
        JSONObject json = project(schema);
        JSONObject mode = json.getJSONObject("properties").getJSONObject("mode");
        assertEquals("eco", mode.getString("const"));
        assertFalse("ANTHROPIC_FULL 不应把 const 转 enum", mode.has("enum"));
    }

    @Test
    public void patternAndLengthPreserved() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("code", CanonicalSchema.string()
                        .pattern("[A-Z]{3}")
                        .minLength(3)
                        .maxLength(3)
                        .build())
                .build();
        JSONObject json = project(schema);
        JSONObject code = json.getJSONObject("properties").getJSONObject("code");
        assertEquals("[A-Z]{3}", code.getString("pattern"));
        assertEquals(3, code.getInt("minLength"));
        assertEquals(3, code.getInt("maxLength"));
    }

    @Test
    public void requiredNotAutoCompleted() throws Exception {
        // ANTHROPIC_FULL 模式下 required 跟随 schema 显式声明,不自动补全
        CanonicalSchema schema = CanonicalSchema.object()
                .property("a", CanonicalSchema.string().build())
                .property("b", CanonicalSchema.integer().build())
                .required("a")  // 只 required a
                .build();
        JSONObject json = project(schema);
        JSONArray required = json.getJSONArray("required");
        assertEquals(1, required.length());
        assertEquals("a", required.get(0));
    }

    @Test
    public void additionalPropertiesFollowsSchemaDeclaration() throws Exception {
        // 显式 true 时跟随(虽然默认 false,允许显式 override)
        CanonicalSchema schema = CanonicalSchema.object()
                .property("a", CanonicalSchema.string().build())
                .additionalProperties(true)
                .build();
        JSONObject json = project(schema);
        assertTrue(json.getBoolean("additionalProperties"));

        // 默认 false
        CanonicalSchema strict = CanonicalSchema.object()
                .property("a", CanonicalSchema.string().build())
                .build();
        assertFalse(project(strict).getBoolean("additionalProperties"));
    }

    @Test
    public void enumArrayPreserved() throws Exception {
        CanonicalSchema schema = CanonicalSchema.object()
                .property("zone", CanonicalSchema.string()
                        .enumValues("driver", "passenger").build())
                .build();
        JSONObject json = project(schema);
        JSONObject zone = json.getJSONObject("properties").getJSONObject("zone");
        JSONArray enumArr = zone.getJSONArray("enum");
        assertEquals(2, enumArr.length());
    }

    @Test
    public void nestedObjectRecurses() throws Exception {
        CanonicalSchema address = CanonicalSchema.object()
                .property("city", CanonicalSchema.string().build())
                .property("zip", CanonicalSchema.string().pattern("\\d{6}").build())
                .required("city", "zip")
                .additionalProperties(false)
                .build();
        CanonicalSchema schema = CanonicalSchema.object()
                .property("destination", address)
                .build();
        JSONObject json = project(schema);
        JSONObject dest = json.getJSONObject("properties").getJSONObject("destination");
        assertEquals("object", dest.getString("type"));
        assertTrue(dest.has("properties"));
        assertTrue(dest.getJSONObject("properties").has("city"));
        assertTrue(dest.getJSONObject("properties").has("zip"));
        JSONArray destRequired = dest.getJSONArray("required");
        assertEquals(2, destRequired.length());
    }
}
