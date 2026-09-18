package com.matrix.agent.task;
import com.matrix.agent.task.redact.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * ModelSanitizer 喂回 LLM conversation——只脱凭据,**保留任务语义**。
 * memory.preference.* 的真实 value 必须保留,否则模型答不出"我喜欢多少度"。
 */
public final class ModelSanitizerTest {
    @Test
    public void masksOpenAiStyleApiKey() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        assertEquals("key=***", sanitizer.sanitize("key=sk-abcd1234abcd1234"));
    }

    @Test
    public void masksAnthropicApiKey() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        assertEquals("key=***", sanitizer.sanitize("key=sk-ant-api03abcd1234abcd1234"));
    }

    @Test
    public void masksGoogleAiStyleKey() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        assertEquals("key=***", sanitizer.sanitize("key=AIzaSyABcd1234abcd1234"));
    }

    @Test
    public void masksBearerAuthorizationHeader() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        assertEquals("Authorization: ***", sanitizer.sanitize("Authorization: Bearer abc.def.ghi"));
    }

    @Test
    public void truncatesLongContentWithinMaxChars() {
        // 截断后总长度必须 ≤ maxChars(旧实现会超)
        ModelSanitizer sanitizer = new ModelSanitizer(30);
        String result = sanitizer.sanitize("1234567890123456789012345678901234567890"); // 40 chars
        assertTrue("expected prefix preserved, got: " + result, result.startsWith("123456789"));
        assertTrue("expected truncation marker, got: " + result, result.contains("[truncated "));
        assertTrue("total length must not exceed maxChars, got: " + result.length(),
                result.length() <= 30);
    }

    @Test
    public void truncateHandlesSmallMaxCharsGracefully() {
        // 极端小 maxChars:至少保留 1 char + 后缀,总长仍受 maxChars 约束(可能 keep=1 时略超)
        ModelSanitizer sanitizer = new ModelSanitizer(15);
        String result = sanitizer.sanitize("12345678901234567890");
        assertTrue(result.contains("[truncated "));
        assertTrue("prefix preserved, got: " + result, result.startsWith("1"));
    }

    /**
     * 关键回归:memory.preference.* 的真实 value 必须保留给模型。
     * 模型需要看到 preferred_temperature=24 才能回答"我喜欢多少度"。
     */
    @Test
    public void preservesMemoryPreferenceValueForModel() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("preferred_temperature", "24");
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "memory.preference.get", "你保存的温度偏好是 24℃",
                observed, true, 3L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("memory.preference.get", observed), result);

        ToolObservation sanitized = sanitizer.sanitize(observation);

        assertEquals("24", sanitized.getResult().getObservedState().get("preferred_temperature"));
        assertTrue("message must keep semantic value",
                sanitized.getResult().getMessage().contains("24℃"));
        assertFalse("must NOT be replaced with placeholder",
                sanitized.getResult().getMessage().contains("<memory>"));
    }

    /** memory.preference.save 也保留 value——后续轮次模型可能要回读这个事实。 */
    @Test
    public void preservesMemoryPreferenceSaveValueForModel() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("preferred_temperature", "24");
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "memory.preference.save", "已记住你的温度偏好：24℃",
                observed, true, 5L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("memory.preference.save", observed), result);

        ToolObservation sanitized = sanitizer.sanitize(observation);

        assertEquals("24", sanitized.getResult().getObservedState().get("preferred_temperature"));
    }

    /** 非 memory 类 capability 也只 mask 凭据,保留语义。 */
    @Test
    public void masksSecretsInNonMemoryCapability() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver.temperature", 24);
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "vehicle.climate.set_temperature", "已设置 sk-abcd1234abcd1234 含密钥",
                observed, true, 8L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("vehicle.climate.set_temperature",
                        args("zone", "driver", "temperature", 24)), result);

        ToolObservation sanitized = sanitizer.sanitize(observation);

        assertTrue("API key must be masked",
                sanitized.getResult().getMessage().contains("***"));
        assertEquals(24, sanitized.getResult().getObservedState().get("driver.temperature"));
    }

    /** Policy 拒绝路径(rejectionReason)也只走 secret mask + 截断。 */
    @Test
    public void sanitizesRejectionReason() {
        ModelSanitizer sanitizer = new ModelSanitizer(1000);
        ToolCall call = new ToolCall("vehicle.adas.set_enabled", args("enabled", true));
        ToolObservation observation = ToolObservation.rejected(call,
                "R3 禁止能力 Bearer abc.def.ghi", true);

        ToolObservation sanitized = sanitizer.sanitize(observation);

        assertTrue(sanitized.isCapabilityBlocked());
        assertTrue(sanitized.getRejectionReason().contains("***"));
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
