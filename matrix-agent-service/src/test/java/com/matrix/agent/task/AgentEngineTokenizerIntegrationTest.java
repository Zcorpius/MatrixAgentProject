package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.token.CharFallbackTokenizer;
import com.matrix.agent.task.token.Tokenizer;
import com.matrix.agent.task.tool.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;

/**
 * AgentEngine Tokenizer 一次性配置与双轨统计测试。
 *
 * <p>主路径仍用 char-based budget 决策,Tokenizer 仅作双轨日志统计。
 * 本测试验证:
 * <ul>
 *   <li>未配置 tokenizer 时任务正常完成(兼容路径);</li>
 *   <li>配置 CharFallbackTokenizer 不影响 budget 决策(任务结果与无 tokenizer 一致);</li>
 *   <li>配置 custom fake 时 tokenizer.count 被调用(双轨统计路径覆盖)。</li>
 * </ul>
 *
 * <p>切主路径后,本测试断言改 token-based 决策(届时 budget 决策会变)。
 */
public final class AgentEngineTokenizerIntegrationTest {
    private InMemoryMemoryStore memoryStore;
    private CapabilityRegistry registry;
    private MockCapabilityProvider provider;
    private SessionManager sessionManager;
    private SessionLockManager sessionLockManager;
    private ToolExecutor toolExecutor;
    private ModelCallExecutor modelCallExecutor;

    @Before
    public void setUp() {
        memoryStore = new InMemoryMemoryStore();
        registry = CapabilityRegistry.createDemoRegistry();
        provider = new MockCapabilityProvider(memoryStore);
        sessionManager = new SessionManager();
        sessionLockManager = new SessionLockManager();
        toolExecutor = new ToolExecutor(4);
        modelCallExecutor = new ModelCallExecutor(2);
    }

    @Test
    public void engineWithoutTokenizerRunsAsBefore() {
        AgentEngine engine = newEngine(null);
        // 不调 setTokenizer——tokenizer=null,char fallback

        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度，然后导航回家", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(StopReason.NO_TOOL_CALL, outcome.getStopReason());
        assertEquals(2, outcome.getResults().size());
    }

    @Test
    public void setTokenizerWithCharFallbackDoesNotChangeDecision() {
        AgentEngine engine = newEngine(CharFallbackTokenizer.INSTANCE);

        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度，然后导航回家", Actor.DRIVER));

        // char fallback 与 null tokenizer 等价——任务结果一致
        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(2, outcome.getResults().size());
    }

    @Test
    public void setTokenizerWithCountingFakeRecordsCallPath() {
        CountingTokenizer counting = new CountingTokenizer();
        AgentEngine engine = newEngine(counting);

        engine.execute(new AgentRequest(
                "把主驾温度调到24度，然后导航回家", Actor.DRIVER));

        // 任务跑完后 tokenizer.count 应被多次调用(双轨日志路径覆盖)
        assertTrue("tokenizer.count 必须被调用至少一次,actual=" + counting.callCount,
                counting.callCount > 0);
    }

    @Test
    public void nullTokenizerConfigurationDoesNotBreak() {
        AgentEngine engine = newEngine(null);

        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
    }

    private AgentEngine newEngine(Tokenizer tokenizer) {
        return new AgentEngine(
                new DemoModelGateway(), modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, new AgentBudget(), null,
                new AgentEngineConfiguration.Builder().tokenizer(tokenizer).build());
    }

    /** Counting fake——记录 count 调用次数,验证双轨日志路径覆盖。 */
    private static final class CountingTokenizer implements Tokenizer {
        int callCount = 0;

        @Override
        public int count(String text) {
            callCount++;
            return text == null ? 0 : text.length() / 4;
        }

        @Override
        public String encoding() {
            return "counting-fake";
        }

        @Override
        public String encodeAndBack(String text) {
            return text == null ? "" : text;
        }
    }
}
