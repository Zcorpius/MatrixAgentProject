package com.matrix.agent.task;

import com.matrix.agent.demo.MockCapabilityProvider;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelGateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;

import com.matrix.agent.data.audit.NoopAuditRepository;

/**
 * 即便 Provider 自己没有 handler-level gate,PolicyEngine 仍守住
 * memory.semantic.save 的显式许可约束。
 *
 * <p>用户原话:"保留 Provider 内的检查作为 defence-in-depth,但不能把它当唯一 gate"。
 * 之前把 gate 放在 MockCapabilityProvider.MemorySemanticSaveHandler,未来接入真实 AAOS
 * Provider 或第三方 CapabilityProvider(可能漏写 handler gate)就会绕过用户许可规则。
 * 把 gate 上移到 PolicyEngine 后,任何 Provider 替换都受同一约束。
 *
 * <p>本测试用 RecordingProvider(实现 CapabilityProvider,execute 内不检查
 * isMemorySaveAllowed)替换 MockCapabilityProvider,验证:
 * <ul>
 *   <li>memorySaveAllowed=false → PolicyEngine 在 Provider 之前 CAPABILITY_REJECTED,execute 0 次</li>
 *   <li>memorySaveAllowed=true → PolicyEngine 放行,execute 至少 1 次</li>
 * </ul>
 */
public final class ProviderSwapStillEnforcedTest {

    private CapabilityRegistry registry;
    private SessionManager sessionManager;
    private SessionLockManager sessionLockManager;
    private ToolExecutor toolExecutor;
    private ModelCallExecutor modelCallExecutor;

    @Before
    public void setUp() {
        registry = CapabilityRegistry.createDemoRegistry();
        sessionManager = new SessionManager();
        sessionLockManager = new SessionLockManager();
        toolExecutor = new ToolExecutor(4);
        modelCallExecutor = new ModelCallExecutor(2);
    }

    private static Map<String, Object> saveArgs() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        return args;
    }

    private static ModelTurn emitSave() {
        return ModelTurn.ofToolCalls(
                Collections.singletonList(new ToolCall("memory.semantic.save", saveArgs())),
                "emit save");
    }

    private AgentEngine newEngine(CapabilityProvider provider, ModelGateway gateway) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager, new DefaultContextUpdater(),
                sessionLockManager, toolExecutor, new AgentBudget(), null,
                AgentEngineConfiguration.defaults());
    }

    @Test
    public void recordingProviderWithoutHandlerGateStillBlockedOnFalse() {
        RecordingProvider provider = new RecordingProvider();
        AgentEngine engine = newEngine(provider, req -> emitSave());
        AgentRequest request = AgentRequest.builder("查天气", Actor.DRIVER)
                .sessionId("swap-1").occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(false)
                .build();
        engine.execute(request);

        assertEquals("RecordingProvider 无 handler gate,PolicyEngine 必须拦在 Provider 之前",
                0, provider.executeCount.get());
    }

    @Test
    public void recordingProviderInvokedWhenMemorySaveFlagTrue() {
        RecordingProvider provider = new RecordingProvider();
        AgentEngine engine = newEngine(provider, req -> emitSave());
        AgentRequest request = AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                .sessionId("swap-2").occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(true)
                .build();
        engine.execute(request);

        assertTrue("memorySaveAllowed=true → PolicyEngine 放行,RecordingProvider 至少调 1 次, got="
                + provider.executeCount.get(),
                provider.executeCount.get() >= 1);
    }

    /**
     * 模拟"未来的真实 AAOS Provider / 第三方实现":实现 CapabilityProvider 但 execute 内
     * 不检查 isMemorySaveAllowed,仅记录调用次数。如果 PolicyEngine 没有 capability-level gate,
     * 这类 Provider 会直接被调到——本测试验证此后这不可能发生。
     */
    private static final class RecordingProvider implements CapabilityProvider {
        final AtomicInteger executeCount = new AtomicInteger();

        @Override
        public ToolResult execute(AgentRequest request, ToolCall call) {
            executeCount.incrementAndGet();
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put(String.valueOf(call.argument("key")), "<memory>");
            return new ToolResult(
                    ToolResult.Status.SUCCESS,
                    call.getCapabilityName(),
                    "ok",
                    observed,
                    true,
                    1L);
        }
    }
}
