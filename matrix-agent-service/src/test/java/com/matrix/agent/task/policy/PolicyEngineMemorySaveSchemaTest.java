package com.matrix.agent.task.policy;

import com.matrix.agent.contract.schema.SchemaValidator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.contract.ToolCall;

/**
 * PolicyEngine 必须用 CapabilityRegistry.memory.semantic.save 的 schema
 * (pattern + maxLength)拒绝非法 key / value,作为模型可见 + 模型可修正的第一道关。
 *
 * <p>用户硬约束:"memory.semantic.save 只接受明确支持的 key namespace,例如 family.* /
 * allergy.* / work.* / fact.*"。"value 最大长度 2048;空字符串拒绝"。
 *
 * <p>Schema 在 CapabilityRegistry 注册时声明 pattern/maxLength(模型可见,可修正参数);
 * Writer 入口再 fail-closed 兜底防 Provider 漏检(见 RoomMemoryWriterSemanticValidationTest)。
 *
 * <p>覆盖 4 cases:
 * <ul>
 *   <li>namespace 外 key(如 random.key)→ PARAMETER_REJECTED</li>
 *   <li>key 超长(> 64 字符)→ PARAMETER_REJECTED</li>
 *   <li>value 超长(> 2048 字符)→ PARAMETER_REJECTED</li>
 *   <li>value 空白 / 纯空格 → PARAMETER_REJECTED(SchemaValidator 的 trim().isEmpty() → EMPTY_STRING)</li>
 * </ul>
 */
public final class PolicyEngineMemorySaveSchemaTest {

    private PolicyEngine engine;

    @Before
    public void setUp() {
        engine = new PolicyEngine(CapabilityRegistry.createDemoRegistry());
    }

    private static AgentRequest allowSaveRequest() {
        return AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(true)  // gate 放行,专注 schema 校验
                .build();
    }

    private static ToolCall saveCall(String key, String value) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", key);
        args.put("value", value);
        return new ToolCall("memory.semantic.save", args);
    }

    @Test
    public void nonNamespaceKeyRejectedByPattern() {
        PolicyDecision d = engine.evaluate(allowSaveRequest(),
                saveCall("random.key", "value"));
        assertFalse("namespace 外 key 必须 DENY", d.isAllowed());
        assertEquals("schema 拒绝是 PARAMETER 级(模型可换 key 重试)",
                PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }

    @Test
    public void overlyLongKeyRejectedByMaxLength() {
        // family. + 60 个 'a' = 66 字符,超过 64 上限
        String longKey = "family." + new String(new char[60]).replace('\0', 'a');
        PolicyDecision d = engine.evaluate(allowSaveRequest(),
                saveCall(longKey, "value"));
        assertFalse("超长 key 必须 DENY", d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }

    @Test
    public void overlyLongValueRejectedByMaxLength() {
        String longValue = new String(new char[2049]).replace('\0', 'x');
        PolicyDecision d = engine.evaluate(allowSaveRequest(),
                saveCall("allergy.peanut", longValue));
        assertFalse("超长 value(> 2048)必须 DENY", d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }

    @Test
    public void blankValueRejectedByEmptyStringCheck() {
        // SchemaValidator STRING 路径:trim().isEmpty() → EMPTY_STRING
        PolicyDecision d = engine.evaluate(allowSaveRequest(),
                saveCall("allergy.peanut", "   "));
        assertFalse("空白 value 必须 DENY(EMPTY_STRING)", d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }
}
