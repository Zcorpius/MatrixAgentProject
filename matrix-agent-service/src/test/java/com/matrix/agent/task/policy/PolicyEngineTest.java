package com.matrix.agent.task.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.task.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * 回归:strict schema 在执行边界拒绝未声明字段、检查 required/type/range/enum。
 * 旧实现把 schema 检查委托给 CapabilityValidator,无法识别模型多塞的字段。
 */
public final class PolicyEngineTest {
    private PolicyEngine engine;

    @Before
    public void setUp() {
        engine = new PolicyEngine(CapabilityRegistry.createDemoRegistry());
    }

    private static AgentRequest driverRequest(String text) {
        return new AgentRequest(text, Actor.DRIVER);
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    /** schema 外的额外字段必须 PARAMETER 拒绝,Provider 不会收到 ToolCall。 */
    @Test
    public void extraSchemaFieldIsRejectedBeforeProvider() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24, "priority", "high"));
        PolicyDecision d = engine.evaluate(driverRequest("把主驾温度调到24度"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
        assertTrue("reason 应当指明未声明字段: " + d.getReason(),
                d.getReason().contains("priority"));
    }

    /** 必需参数缺失(schema 内) → PARAMETER 拒绝。 */
    @Test
    public void missingRequiredParameterIsRejected() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver"));  // 缺 temperature
        PolicyDecision d = engine.evaluate(driverRequest("把主驾温度调到24度"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
        assertTrue(d.getReason().contains("temperature"));
    }

    /** INTEGER 类型 + 越界值 → PARAMETER 拒绝。 */
    @Test
    public void outOfRangeIntegerIsRejected() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 31));
        PolicyDecision d = engine.evaluate(driverRequest("把主驾温度调到31度"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }

    /** STRING + 枚举值不在允许集合 → PARAMETER 拒绝。 */
    @Test
    public void enumValueOutsideAllowedIsRejected() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "rear_left", "temperature", 24));  // rear_left 不在 enum
        PolicyDecision d = engine.evaluate(driverRequest("把后排左侧温度调到24度"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }

    /** INTEGER + 传小数 → PARAMETER 拒绝。 */
    @Test
    public void nonIntegerValueIsRejectedForIntegerParam() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24.5));
        PolicyDecision d = engine.evaluate(driverRequest("把主驾温度调到24.5度"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
    }

    /** 合法 schema 调用应当 ALLOW,不被 strict schema 误拒。 */
    @Test
    public void legalSchemaCallIsAllowed() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(driverRequest("把主驾温度调到24度"), call);
        assertTrue("strict schema 不应误拒合法调用: " + d.getReason(), d.isAllowed());
    }

    /** knowledge.answer 只有 question 参数,加额外字段也要拒绝。 */
    @Test
    public void extraFieldOnReadOnlyCapabilityIsRejected() {
        ToolCall call = new ToolCall("knowledge.answer",
                args("question", "今天几号", "user_id", "123"));
        PolicyDecision d = engine.evaluate(driverRequest("今天几号"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
        assertTrue(d.getReason().contains("user_id"));
    }

    /** navigation.start_route + 额外 route 字段 → PARAMETER 拒绝。 */
    @Test
    public void extraFieldOnNavigationIsRejected() {
        ToolCall call = new ToolCall("navigation.start_route",
                args("destination", "公司", "avoid_highway", true));
        PolicyDecision d = engine.evaluate(driverRequest("导航去公司"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER, d.getRejectionType());
        assertTrue(d.getReason().contains("avoid_highway"));
    }

    /**
     * 端到端:多目标用户意图 + 写操作 → CAPABILITY 拒绝(在 strict schema 之前)。
     */
    @Test
    public void multiTargetIntentBlocksBeforeSchemaCheck() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(
                new AgentRequest("主驾和副驾都调到24度", Actor.DRIVER), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
        assertTrue(d.getReason().contains("多个区域目标"));
    }

    /**
     * 运行时拒绝 reason 字符串不应硬编码版本号。
     *
     * <p>背景:此前 MULTI_TARGET 拒绝文案写"V0.x 不支持一句话同时操作多 zone",把开发期版本号
     * 硬编码进 PolicyDecision.reason —— 该字段会进 ToolObservation 喂给模型、进 Trajectory 渲染到 UI。
     * 版本号会让用户困惑、升级后变成陈年误导、暗示"未来版本会放开"错承诺,且把"安全设计选择"
     * 说成"能力未实现"。改成强调安全的措辞后,本测试断言 reason 永不含 V0.x 子串,防回归。
     */
    @Test
    public void rejectionReasonMustNotLeakVersionNumber() {
        ToolCall multiTargetCall = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision multiTarget = engine.evaluate(
                new AgentRequest("主驾和副驾都调到24度", Actor.DRIVER), multiTargetCall);
        assertFalse(multiTarget.isAllowed());
        assertFalse("MULTI_TARGET reason 不应含版本号: " + multiTarget.getReason(),
                multiTarget.getReason().matches(".*V0\\.\\d+.*"));

        ToolCall conflictCall = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision conflict = engine.evaluate(
                new AgentRequest("不要调主驾", Actor.DRIVER), conflictCall);
        assertFalse(conflict.isAllowed());
        assertFalse("CONFLICT reason 不应含版本号: " + conflict.getReason(),
                conflict.getReason().matches(".*V0\\.\\d+.*"));
    }
}
