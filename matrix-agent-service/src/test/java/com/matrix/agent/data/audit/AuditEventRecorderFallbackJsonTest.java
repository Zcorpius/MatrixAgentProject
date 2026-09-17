package com.matrix.agent.data.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * buildPreToolPayload fallback 路径必须返回合法 JSON。
 *
 * <p>旧 fallback 用 {@code JSONObject.quote(toolName)} 返回带引号字符串(如 {@code "toolName"})
 * 后拼到已有 {@code "..."} 内,产生 {@code "tool":""toolName""} 双重引号——非法 JSON,
 * {@code new JSONObject(payload)} 会抛。改用 fallback {@code new JSONObject().put(...).toString()}
 * 保证合法。
 *
 * <p>正常路径(JSONObject.put)已合法;本测试通过自引用 Map 触发 JSONException 进 fallback,
 * 验证 fallback 输出可被 {@code new JSONObject(...)} round-trip 解析。
 */
public final class AuditEventRecorderFallbackJsonTest {

    @Test
    public void selfReferentialMapTriggersFallbackReturnsLegalJson() throws Exception {
        Map<String, Object> bad = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("loop", bad);
        bad.put("loop", inner);

        String payload = AuditEventRecorder.buildPreToolPayload("vehicle_state.get", bad, null);

        try {
            JSONObject parsed = new JSONObject(payload);
            assertEquals("vehicle_state.get", parsed.getString("tool"));
            assertTrue("fallback 必须有 args 字段", parsed.has("args"));
        } catch (Throwable ex) {
            throw new AssertionError("V0.5.2-rev P3-1:fallback 必须返回合法 JSON,实际=" + payload
                    + " parse 失败 cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @Test
    public void nullToolNameReturnsLegalJson() throws Exception {
        // JSONObject.put(key, null) 在 Android org.json 实现里会 REMOVE key(标准行为),
        // 但 payload 仍是合法 JSON(空对象)。本测试只验证 payload 可解析,不强求 tool 字段。
        String payload = AuditEventRecorder.buildPreToolPayload(null, null, null);
        JSONObject parsed = new JSONObject(payload);
        assertTrue("null toolName payload 必须是合法 JSON 对象(空或含 tool)",
                parsed.length() == 0 || parsed.has("tool"));
    }

    @Test
    public void emptyStringToolNameReturnsLegalJson() throws Exception {
        String payload = AuditEventRecorder.buildPreToolPayload("", null, null);
        JSONObject parsed = new JSONObject(payload);
        assertEquals("", parsed.getString("tool"));
    }

    @Test
    public void normalPathStillReturnsLegalJson() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", "DRIVER");
        String payload = AuditEventRecorder.buildPreToolPayload("climate.set", args, null);
        JSONObject parsed = new JSONObject(payload);
        assertEquals("climate.set", parsed.getString("tool"));
        assertEquals("DRIVER", parsed.getJSONObject("args").getString("zone"));
    }
}
