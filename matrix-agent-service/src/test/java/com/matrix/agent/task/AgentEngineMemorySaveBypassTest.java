package com.matrix.agent.task;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelGateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;

import com.matrix.agent.data.audit.NoopAuditRepository;

/**
 * PolicyEngine deny → AgentEngine continue 短路,Provider.execute 永不被调。
 *
 * <p>验证用户硬约束:"Engine 不得把该调用提交给 ToolExecutor / Provider"。AgentEngine
 * 在 PolicyDecision.isAllowed()==false 时 continue 短路(AgentEngine.java:571-596),
 * MockCapabilityProvider 的 MemorySemanticSaveHandler 不应被触及——
 * 既证明 PolicyEngine 是主 gate,又证明 handler 的 defence-in-depth 此刻不需要触发。
 */
public final class AgentEngineMemorySaveBypassTest {

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
        args.put("value", "严重过敏");
        return args;
    }

    private static ModelTurn emitSave() {
        return ModelTurn.ofToolCalls(
                Collections.singletonList(new ToolCall("memory.semantic.save", saveArgs())),
                "emit memory.semantic.save");
    }

    private AgentEngine newEngine(CapabilityProvider provider, ModelGateway gateway) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager, new DefaultContextUpdater(),
                sessionLockManager, toolExecutor, new AgentBudget(), null,
                AgentEngineConfiguration.defaults());
    }

    @Test
    public void providerNotCalledWhenMemorySaveFlagFalse() {
        CountingProvider provider = new CountingProvider(
                new MockCapabilityProvider(new InMemoryMemoryStore()));
        AgentEngine engine = newEngine(provider, req -> emitSave());
        AgentRequest request = AgentRequest.builder("查天气", Actor.DRIVER)
                .sessionId("s1").occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(false)
                .build();
        engine.execute(request);

        assertEquals("PolicyEngine DENY → Provider.execute 必须 0 次",
                0, provider.executeCount.get());
    }

    @Test
    public void providerCalledOnceWhenMemorySaveFlagTrue() {
        CountingProvider provider = new CountingProvider(
                new MockCapabilityProvider(new InMemoryMemoryStore()));
        AgentEngine engine = newEngine(provider, req -> emitSave());
        AgentRequest request = AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                .sessionId("s2").occupantZone(VehicleZone.DRIVER)
                .memorySaveAllowed(true)
                .build();
        engine.execute(request);

        assertTrue("PolicyEngine ALLOW → Provider.execute 至少 1 次, got="
                + provider.executeCount.get(),
                provider.executeCount.get() >= 1);
    }

    /** 包一层 delegate 计数,既能验证 PolicyEngine deny 时不进 Provider,也能保留 MockProvider 真实行为。 */
    private static final class CountingProvider implements CapabilityProvider {
        final AtomicInteger executeCount = new AtomicInteger();
        private final CapabilityProvider delegate;

        CountingProvider(CapabilityProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolResult execute(AgentRequest request, ToolCall call) {
            executeCount.incrementAndGet();
            return delegate.execute(request, call);
        }
    }
}
