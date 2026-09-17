package com.matrix.agent.task.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PolicyEngine 必须用 readOnlyHint 强制拒绝写 capability。
 *
 * <p>IntentClassifier 在 LLM 调用前给 readOnlyHint 做保守分类,让 TaskScheduler 主驾抢占可触达。
 * 但模型输出不可信——LLM 错误或 prompt injection 后,可能对"查一下空调状态"等查询命令返回
 * climate.set_temperature 等写 capability。若 PolicyEngine 不强制,任务级 readOnlyHint=true
 * 的"被抢占查询任务"实际跑写操作,半路被 cancel 时违反"写操作不可半路中断"的安全契约。
 *
 * <p>修复:readOnlyHint=true 时,所有 writeOperation=true 的 capability 一律 CAPABILITY
 * 拒绝(不可上诉)。误判代价最多是"查询任务失败",不会放出或中断写车控。
 */
public final class PolicyEngineReadOnlyHintTest {

    private PolicyEngine engine;

    @Before
    public void setUp() {
        engine = new PolicyEngine(CapabilityRegistry.createDemoRegistry());
    }

    private static AgentRequest readOnlyRequest(Actor actor, String text) {
        return AgentRequest.builder(text, actor)
                .readOnlyHint(true)
                .build();
    }

    private static AgentRequest writeRequest(Actor actor, String text) {
        return AgentRequest.builder(text, actor)
                .readOnlyHint(false)
                .build();
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    @Test
    public void readOnlyHintTrueRejectsWriteCapability() {
        AgentRequest request = readOnlyRequest(Actor.DRIVER, "查一下空调状态");
        ToolCall writeCall = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(request, writeCall);
        assertFalse("readOnlyHint=true 时写 capability 必须 DENY", d.isAllowed());
        assertEquals("写操作违反应 CAPABILITY 拒绝(不可上诉)",
                PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
        assertTrue("reason 应说明 readOnlyHint", d.getReason().contains("只读"));
    }

    @Test
    public void readOnlyHintTrueAllowsReadCapability() {
        AgentRequest request = readOnlyRequest(Actor.DRIVER, "查电量");
        ToolCall readCall = new ToolCall("vehicle.info.get_battery",
                java.util.Collections.emptyMap());
        PolicyDecision d = engine.evaluate(request, readCall);
        assertTrue("readOnlyHint=true 时读 capability 应 ALLOW, got reason=" + d.getReason(),
                d.isAllowed());
    }

    @Test
    public void readOnlyHintFalseAllowsWriteCapability() {
        AgentRequest request = writeRequest(Actor.DRIVER, "把主驾温度调到24度");
        ToolCall writeCall = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(request, writeCall);
        assertTrue("readOnlyHint=false 时正常写 capability 应 ALLOW", d.isAllowed());
    }

    @Test
    public void promptInjectionCannotBypassReadOnlyHint() {
        // 模拟 prompt injection:用户说"查一下空调状态"(readOnlyHint=true),
        // 但模型返回 climate.set_temperature。PolicyEngine 必须拒绝,无论参数多合法。
        AgentRequest request = readOnlyRequest(Actor.DRIVER, "查一下空调状态");
        ToolCall injectedCall = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        PolicyDecision d = engine.evaluate(request, injectedCall);
        assertFalse("prompt injection 不能绕过 readOnlyHint", d.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
    }

    @Test
    public void readOnlyHintBlocksAllWriteCapabilities() {
        // 多个写 capability 都要堵:climate / seat / navigation / memory.preference.save
        AgentRequest request = readOnlyRequest(Actor.PASSENGER, "查一下");

        ToolCall climateWrite = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "passenger", "temperature", 24));
        ToolCall seatWrite = new ToolCall("vehicle.seat.set_heating_level",
                args("zone", "passenger", "level", 2));
        ToolCall navWrite = new ToolCall("vehicle.navigation.start_route",
                args("destination", "公司"));
        ToolCall memSave = new ToolCall("vehicle.memory.preference.save",
                args("key", "temp", "value", "24"));

        for (ToolCall call : new ToolCall[]{climateWrite, seatWrite, navWrite, memSave}) {
            PolicyDecision d = engine.evaluate(request, call);
            assertFalse("capability=" + call.getCapabilityName() + " 应被 readOnlyHint 拒绝",
                    d.isAllowed());
            assertEquals(PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
        }
    }

    @Test
    public void readOnlyHintTrueRejectsEvenIfOtherChecksWouldAlsoFail() {
        // 即便 capability 本身也有问题(如 schema 错),readOnlyHint 应优先 CAPABILITY 拒绝,
        // 防止 PARAMETER 拒绝让模型换参数重试(那会引导模型继续写)。
        AgentRequest request = readOnlyRequest(Actor.DRIVER, "查电量");
        ToolCall badCall = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 999));  // range 越界
        PolicyDecision d = engine.evaluate(request, badCall);
        assertFalse(d.isAllowed());
        assertEquals("readOnlyHint 应在 schema/range 校验之前 CAPABILITY 拒绝",
                PolicyDecision.RejectionType.CAPABILITY, d.getRejectionType());
    }
}
