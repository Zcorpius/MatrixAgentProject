package com.matrix.agent.task.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleState;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.task.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

/**
 * PolicyEngine vehicle state 前置约束测试。
 *
 * <p>验证:
 * <ol>
 *   <li>vehicle state 满足时正常通过</li>
 *   <li>vehicle state 不满足(行驶中调温)→ CAPABILITY 拒绝</li>
 *   <li>vehicle state 检查在 schema 检查之前(CAPABILITY 优先于 PARAMETER)</li>
 *   <li>未声明 requiredVehicleStates 的 capability 不受影响</li>
 * </ol>
 */
public final class PolicyEngineVehicleStateTest {
    private PolicyEngine engine;

    @Before
    public void setUp() {
        engine = new PolicyEngine(CapabilityRegistry.createDemoRegistry());
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static AgentRequest driverRequestParked(String text) {
        return AgentRequest.builder(text, Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .vehicleState(VehicleState.satisfyAllPredicates())
                .build();
    }

    private static AgentRequest driverRequestMoving(String text) {
        return AgentRequest.builder(text, Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .vehicleState(VehicleState.builder()
                        .gear(VehicleState.Gear.D)
                        .speedKmh(40)
                        .engineRunning(true)
                        .charging(false)
                        .batteryPercent(80)
                        .build())
                .build();
    }

    /** 默认 mock state(gear=P)→ PARKED_ONLY 通过,set_temperature 正常 schema 校验。 */
    @Test
    public void vehicleStateAllowsWhenParked() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(driverRequestParked("把主驾温度调到24度"), call);
        assertTrue("PARKED + 合法参数应通过:" + d.getReason(), d.isAllowed());
    }

    /** gear=D 行驶中调温 → CAPABILITY 拒绝。 */
    @Test
    public void vehicleStateBlocksWriteWhenMoving() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(driverRequestMoving("把主驾温度调到24度"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
        assertTrue("reason 应包含 vehicle state: " + d.getReason(),
                d.getReason().contains("vehicle state"));
    }

    /**
     * vehicle state 不满足 + 参数非法 → CAPABILITY 优先(PARAMETER 不暴露给模型重试)。
     * 因为 vehicle state 是物理事实,换参数解决不了。
     */
    @Test
    public void vehicleStateCheckHappensBeforeSchemaCheck() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 31));  // temperature 越界
        PolicyDecision d = engine.evaluate(driverRequestMoving("把主驾温度调到31度"), call);
        assertFalse(d.isAllowed());
        assertEquals("vehicle state 优先于 schema 错误",
                PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
    }

    /** 未声明 requiredVehicleStates 的 capability 不受影响——读操作在任何 state 都能调。 */
    @Test
    public void noRequiredStatesMeansNoCheck() {
        ToolCall call = new ToolCall("knowledge.answer", args("question", "今天几号"));
        PolicyDecision d = engine.evaluate(driverRequestMoving("今天几号"), call);
        assertTrue("knowledge.answer 无 vehicle state 约束应通过:" + d.getReason(), d.isAllowed());
    }

    /** navigation.start_route 同样标 PARKED_ONLY——行驶中不能开始导航。 */
    @Test
    public void navigationRequiresParked() {
        ToolCall call = new ToolCall("navigation.start_route",
                args("destination", "公司"));
        PolicyDecision d = engine.evaluate(driverRequestMoving("导航去公司"), call);
        assertFalse(d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
    }
}
