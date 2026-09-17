package com.matrix.agent.architecture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 运行时架构门禁：这些约束保护依赖方向，不允许以“方便”为由重新耦合回去。
 */
public final class RuntimeBoundaryTest {
    @Test
    public void engineDependsOnTaskPortsNotPersistenceImplementations() throws IOException {
        String source = source("com/matrix/agent/task/AgentEngine.java");
        assertFalse("Engine must depend on TaskAuditSink, not AuditRepository",
                source.contains("data.audit.AuditRepository"));
        assertFalse("Engine must depend on TaskMemoryWriter, not MemoryWriter",
                source.contains("data.memory.MemoryWriter"));
    }

    @Test
    public void repositoryIsAThinRuntimeFacade() throws IOException {
        String source = source("com/matrix/agent/task/AgentRuntimeRepository.java");
        assertTrue("dispatch must be delegated to the coordinator",
                source.contains("taskDispatchCoordinator.dispatch"));
        assertTrue("data reset must be delegated to the coordinator",
                source.contains("userDataResetCoordinator.clear"));
        assertFalse("legacy dispatch implementation must not return", source.contains("dispatchLegacy"));
        assertFalse("legacy reset implementation must not return",
                source.contains("clearUserDataDetailedLegacy"));
    }

    @Test
    public void modelProtocolClientDoesNotOwnHttpConnectionLifecycle() throws IOException {
        String source = source("com/matrix/agent/model/ModelApiClient.java");
        assertTrue("ModelApiClient must delegate to transport", source.contains("HTTP_TRANSPORT.post"));
        assertFalse("HTTP lifecycle belongs in JsonHttpTransport",
                source.contains("HttpURLConnection"));
        assertTrue("OpenAI wire encoding belongs in its protocol adapter",
                source.contains("OpenAiToolProtocol."));
        assertTrue("Anthropic wire encoding belongs in its protocol adapter",
                source.contains("AnthropicToolProtocol."));
        assertTrue("Gemini wire encoding belongs in its protocol adapter",
                source.contains("GeminiToolProtocol."));
        assertFalse("provider legacy implementations must not remain in the facade",
                source.contains("ResponseLegacy"));
    }

    @Test
    public void appContainerDelegatesTaskAssemblyToRuntimeGraph() throws IOException {
        String source = source("com/matrix/agent/host/AppContainer.java");
        assertTrue("task assembly belongs to TaskRuntimeGraph",
                source.contains("new TaskRuntimeGraph(taskDependencies)"));
        assertFalse("AppContainer must not mutate a partly-built Engine",
                source.contains("engine.set"));
    }

    @Test
    public void appContainerDelegatesEncryptedMemoryAssemblyToMemoryGraph() throws IOException {
        String source = source("com/matrix/agent/host/AppContainer.java");
        assertTrue("memory assembly belongs to MemoryRuntimeGraph",
                source.contains("new MemoryRuntimeGraph("));
        assertFalse("Room writer construction must not leak back into AppContainer",
                source.contains("new com.matrix.agent.data.memory.RoomMemoryWriter("));
        assertFalse("preference migration belongs to MemoryRuntimeGraph",
                source.contains("RoomMemoryMigrator.fromSharedPreferences"));
    }

    @Test
    public void appContainerDelegatesModelAndAuditAssemblyToDedicatedGraphs() throws IOException {
        String source = source("com/matrix/agent/host/AppContainer.java");
        assertTrue("model assembly belongs to ModelRuntimeGraph",
                source.contains("new ModelRuntimeGraph("));
        assertTrue("audit assembly belongs to AuditRuntimeGraph",
                source.contains("new AuditRuntimeGraph("));
        assertFalse("AppContainer must not construct the remote model repository directly",
                source.contains("new ModelGatewayRepository("));
        assertFalse("AppContainer must not construct Room audit implementation directly",
                source.contains("new RoomAuditRepository("));
    }

    @Test
    public void modelDecisionHasOneCanonicalTurnRepresentation() throws IOException {
        String gateway = source("com/matrix/agent/model/LlmModelGateway.java");
        String planner = source("com/matrix/agent/model/LlmPlanner.java");
        assertTrue("compatibility model must return ModelTurn directly",
                planner.contains("public ModelTurn decide("));
        assertFalse("gateway must not reconstruct a deprecated plan", gateway.contains("TaskPlan"));
        assertFalse("planner must not depend on a deprecated plan", planner.contains("TaskPlan"));
    }

    private static String source(String relative) throws IOException {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path direct = workdir.resolve("src/main/java").resolve(relative);
        Path fromRoot = workdir.resolve("matrix-agent-service/src/main/java").resolve(relative);
        Path file = Files.exists(direct) ? direct : fromRoot;
        assertTrue("source file must exist: " + file, Files.exists(file));
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
