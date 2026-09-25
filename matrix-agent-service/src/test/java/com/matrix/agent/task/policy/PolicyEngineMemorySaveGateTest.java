package com.matrix.agent.task.policy;

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
 * PolicyEngine 必须用 capability-level CAPABILITY_REJECTED 拒绝
 * memory.semantic.save + isMemorySaveAllowed=false 的组合。
 *
 * <p>用户硬约束:"应该在 PolicyEngine.evaluate() 中强制 capability == memory.semantic.save
 * && !request.isMemorySaveAllowed() → denyCapability。保留 Provider 内的检查作为
 * defence-in-depth,但不能把它当唯一 gate"。
 *
 * <p>gate 必须在 schema 校验之前——memorySaveAllowed=false 是 capability-level 不可上诉
 * (模型只能放弃或换 capability),不应让模型通过修参数绕过。同语义:R3 / vehicleState /
 * readOnlyHint + writeOperation 都是 capability-level fail-closed。
 */
public final class PolicyEngineMemorySaveGateTest {

    private PolicyEngine engine;

    @Before
    public void setUp() {
        engine = new PolicyEngine(CapabilityRegistry.createDemoRegistry());
    }

    private static Map<String, Object> semanticArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        return args;
    }

    @Test
    public void memorySaveAllowedFalseRejectsSemanticSaveAsCapability() {
        AgentRequest request = AgentRequest.builder("查天气", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(false)
                .build();
        ToolCall call = new ToolCall("memory.semantic.save", semanticArgs());
        PolicyDecision d = engine.evaluate(request, call);

        assertFalse("memorySaveAllowed=false + memory.semantic.save 必须 DENY", d.isAllowed());
        assertEquals("capability-level 拒绝(不可上诉,模型换参数无效)",
                PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
        assertTrue("reason 应含显式长期记忆引导词,帮助模型纠正: " + d.getReason(),
                d.getReason().contains("显式") || d.getReason().contains("记住"));
    }

    @Test
    public void memorySaveAllowedTrueAllowsSemanticSave() {
        AgentRequest request = AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(true)
                .build();
        ToolCall call = new ToolCall("memory.semantic.save", semanticArgs());
        PolicyDecision d = engine.evaluate(request, call);

        assertTrue("memorySaveAllowed=true + 合法 schema → ALLOW, got reason=" + d.getReason(),
                d.isAllowed());
    }

    @Test
    public void saveFlagDoesNotAuthorizeAnUnrelatedClause() {
        AgentRequest request = AgentRequest.builder("记住我对花生过敏，顺便把空调调到24度", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(true)
                .build();
        ToolCall call = new ToolCall("memory.preference.save",
                Map.of("key", "preferred_temperature", "value", "24"));

        PolicyDecision decision = engine.evaluate(request, call);

        assertFalse(decision.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, decision.getRejectionType());
    }

    @Test
    public void forgetInstructionDoesNotAuthorizeAnotherClause() {
        AgentRequest request = AgentRequest.builder("忘记我的花生过敏，顺便调空调", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .build();
        ToolCall call = new ToolCall("memory.preference.delete",
                Map.of("key", "preferred_temperature"));

        PolicyDecision decision = engine.evaluate(request, call);

        assertFalse(decision.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, decision.getRejectionType());
    }

    @Test
    public void nonSemanticSaveCapabilityUnaffectedByGate() {
        // memorySaveAllowed=false 但调用 climate 写 capability → gate 不触发,其他检查放行
        AgentRequest request = AgentRequest.builder("把主驾温度调到24度", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(false)
                .build();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", "driver");
        args.put("temperature", 24);
        ToolCall call = new ToolCall("vehicle.climate.set_temperature", args);
        PolicyDecision d = engine.evaluate(request, call);

        assertTrue("climate 写操作不应受 memorySaveAllowed gate 影响, got reason=" + d.getReason(),
                d.isAllowed());
    }
}
