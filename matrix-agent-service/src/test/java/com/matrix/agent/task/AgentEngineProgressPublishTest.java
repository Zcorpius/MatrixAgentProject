package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.port.TaskProgressSink;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.task.tool.ToolExecutor;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Engine 进度发布点（I3 §6.2）：进入模型前 PLANNING、进入能力执行前 EXECUTING；
 * 直答路径只有 PLANNING；多轮 loop 交替 PLANNING/EXECUTING；无任务 requestId 不发布。
 */
public final class AgentEngineProgressPublishTest {

    private static final class RecordingSink implements TaskProgressSink {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void onModelPlanning(String runtimeRequestId) {
            events.add("PLANNING:" + runtimeRequestId);
        }

        @Override public void onCapabilityExecuting(String runtimeRequestId,
                String capabilityName) {
            events.add("EXECUTING:" + capabilityName);
        }
    }

    private static ModelGateway directAnswerGateway() {
        return request -> ModelTurn.directAnswer("已理解您的请求");
    }

    /** 单射：首轮一个 tool_call，看到 observation 后直答（对齐 AgentEngineTest.oneShot）。 */
    private static ModelGateway oneToolThenAnswerGateway(ToolCall call) {
        return request -> {
            for (com.matrix.agent.contract.AgentMessage message : request.getConversation()) {
                if (message.getRole() == com.matrix.agent.contract.AgentMessage.Role.TOOL) {
                    return ModelTurn.directAnswer("已完成");
                }
            }
            return ModelTurn.ofToolCalls(Collections.singletonList(call), "progress-test");
        };
    }

    private static AgentEngine engineWith(ModelGateway gateway, TaskProgressSink sink) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        return new AgentEngine(gateway, new ModelCallExecutor(2),
                new com.matrix.agent.task.policy.PolicyEngine(registry), registry,
                new MockCapabilityProvider(new InMemoryMemoryStore()), new SessionManager(),
                (sessionContext, call, result) -> { }, new SessionLockManager(),
                new ToolExecutor(4), new AgentBudget(), null,
                new AgentEngineConfiguration.Builder().taskProgressSink(sink).build());
    }

    @Test
    public void directAnswerPathPublishesPlanningOnly() {
        RecordingSink sink = new RecordingSink();
        AgentOutcome outcome = engineWith(directAnswerGateway(), sink)
                .execute(new AgentRequest("你好", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(List.of("PLANNING:" + outcome.getRequestId()), sink.events);
    }

    @Test
    public void toolLoopPublishesPlanningExecutingThenPlanningForFinalTurn() {
        RecordingSink sink = new RecordingSink();
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                Map.of("zone", "DRIVER", "temperature", 24));
        AgentOutcome outcome = engineWith(oneToolThenAnswerGateway(call), sink)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.DRIVER)
                        .sessionId("progress-loop").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(List.of(
                "PLANNING:" + outcome.getRequestId(),
                "EXECUTING:vehicle.climate.set_temperature",
                "PLANNING:" + outcome.getRequestId()), sink.events);
    }

    @Test
    public void defaultConfigurationPublishesNothing() {
        RecordingSink sink = new RecordingSink();
        // 未装配 sink 的默认配置（旧构造器路径）行为与端口引入前完全一致。
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        AgentEngine engine = new AgentEngine(directAnswerGateway(), new ModelCallExecutor(2),
                new com.matrix.agent.task.policy.PolicyEngine(registry), registry,
                new MockCapabilityProvider(new InMemoryMemoryStore()), new SessionManager(),
                (sessionContext, call, result) -> { }, new SessionLockManager(),
                new ToolExecutor(4));
        engine.execute(new AgentRequest("你好", Actor.DRIVER));
        assertTrue(sink.events.isEmpty());
    }
}
