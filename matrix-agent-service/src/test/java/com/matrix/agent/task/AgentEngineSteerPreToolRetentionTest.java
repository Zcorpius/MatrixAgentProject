package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.tool.ToolCall;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;

/**
 * AgentEngine.pre-tool Steer 保留测试。
 *
 * <p>旧 hasDeferredSteer 调 drain() 掏空队列,即使队列里只有 REPROMPT / FORCE_TOOL(无 DEFER),
 * 这些指令也永久丢失。改用 peek,非 DEFER 指令保留给下一轮 drain。
 *
 * <p>测试场景:
 * <ol>
 *   <li>第 1 轮 LLM 调用中(模拟阻塞),外部线程 offer REPROMPT(无 DEFER)</li>
 *   <li>第 1 轮 LLM 返回 tool_call → 进入 hasDeferredSteer 检查</li>
 *   <li>旧 drain:队列被掏空 → hasDeferredSteer=false → 执行 tool_call → iter 2 drainSteerBeforeLlm 拿到空队列</li>
 *   <li>新 peek:队列不动 → hasDeferredSteer=false → 执行 tool_call → iter 2 drainSteerBeforeLlm 拿到 REPROMPT</li>
 * </ol>
 *
 * <p>断言:第 2 轮 gateway 应看到追加的 REPROMPT 文本(证明 peek 保留了指令)。
 */
public final class AgentEngineSteerPreToolRetentionTest {

    @Test
    public void repromptOfferedDuringLlmCallIsRetainedForNextDrain() throws Exception {
        SteerMailbox mailbox = new SteerMailbox();
        ToolCall battery = new ToolCall("vehicle.info.get_battery", Collections.<String, Object>emptyMap());

        CountDownLatch llmStarted = new CountDownLatch(1);
        CountDownLatch repromptOffered = new CountDownLatch(1);
        AtomicReference<String> capturedReprompt = new AtomicReference<>("(not seen)");

        ModelGateway gateway = req -> {
            long userCount = req.getConversation().stream()
                    .filter(m -> m.getRole() == AgentMessage.Role.USER).count();
            if (userCount >= 2) {
                req.getConversation().stream()
                        .filter(m -> m.getRole() == AgentMessage.Role.USER)
                        .map(AgentMessage::getContent)
                        .filter("改下查询条件"::equals)
                        .findFirst()
                        .ifPresent(capturedReprompt::set);
                return ModelTurn.directAnswer("已结合新指令");
            }
            llmStarted.countDown();
            try {
                repromptOffered.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return ModelTurn.ofToolCalls(Collections.singletonList(battery), "battery");
        };

        CapabilityProvider provider = (req, call1) -> new ToolResult(
                ToolResult.Status.SUCCESS, call1.getCapabilityName(), "ok",
                Collections.<String, Object>emptyMap(), true, 5);

        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        AgentEngine engine = new AgentEngine(gateway,
                new ModelCallExecutor(2),
                new PolicyEngine(registry),
                registry,
                provider,
                new SessionManager(),
                new DefaultContextUpdater(),
                new SessionLockManager(),
                new ToolExecutor(2),
                new AgentBudget(),
                mailbox);

        Thread offerer = new Thread(() -> {
            try {
                llmStarted.await(2, TimeUnit.SECONDS);
                mailbox.offer("retain-session", Steer.reprompt("改下查询条件"));
                repromptOffered.countDown();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "reprompt-offerer");
        offerer.setDaemon(true);
        offerer.start();

        AgentOutcome outcome = engine.execute(
                AgentRequest.builder("查电量", Actor.DRIVER)
                        .sessionId("retain-session")
                        .build());

        offerer.join(3_000);
        assertEquals("任务应 SUCCEEDED 完成",
                TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals("第 2 轮 LLM 应看到追加的 REPROMPT 文本",
                "改下查询条件", capturedReprompt.get());
        assertTrue("mailbox 应被 drain 干净",
                mailbox.pendingCount("retain-session") == 0);
    }
}
