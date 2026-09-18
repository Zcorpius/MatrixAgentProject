package com.matrix.agent.architecture;
import com.matrix.agent.host.rpc.*;
import com.matrix.agent.host.di.*;
import com.matrix.agent.task.compress.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.demo.MockCapabilityProvider;

import com.matrix.agent.contract.ModelTurn;

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
    public void persistenceApisUseFlatCommandsRatherThanTaskObjects() throws IOException {
        String[] dataSources = {
                "com/matrix/agent/data/audit/AuditRepository.java",
                "com/matrix/agent/data/audit/RoomAuditRepository.java",
                "com/matrix/agent/data/audit/NoopAuditRepository.java",
                "com/matrix/agent/data/audit/AuditEventRecorder.java",
                "com/matrix/agent/data/memory/MemoryWriter.java",
                "com/matrix/agent/data/memory/RoomMemoryWriter.java",
        };
        for (String path : dataSources) {
            assertFalse("data persistence must not depend on task objects: " + path,
                    source(path).contains("com.matrix.agent.task."));
        }
    }

    @Test
    public void dispatchRecoveryPublishesThroughTaskAuditPort() throws IOException {
        String source = source("com/matrix/agent/task/scheduler/TaskDispatchCoordinator.java");
        assertTrue("dispatch must depend on the task audit port", source.contains("TaskAuditSink"));
        assertFalse("dispatch must not couple to the data repository",
                source.contains("data.audit.AuditRepository"));
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
    public void taskRuntimeConsumesHostAdaptedInputNotVoiceOrMockTypes() throws IOException {
        String repository = source("com/matrix/agent/task/AgentRuntimeRepository.java");
        assertTrue("task runtime must expose its own input contract",
                repository.contains("AgentInvocation"));
        assertFalse("task must not import voice DTOs", repository.contains("com.matrix.agent.voice."));
        assertFalse("a demo provider must not leak into the repository API",
                repository.contains("MockCapabilityProvider"));

        String graph = source("com/matrix/agent/host/di/TaskRuntimeGraph.java");
        assertTrue("host graph must depend on the capability abstraction",
                graph.contains("CapabilityProvider provider"));
        assertFalse("host graph must not encode the demo provider type",
                graph.contains("MockCapabilityProvider provider"));
    }

    @Test
    public void androidEntryPointsDependOnDomainRuntimeContractsNotHostImplementations()
            throws IOException {
        String[] downloadEntryPoints = {
                "com/matrix/agent/download/DownloadService.java",
                "com/matrix/agent/download/ModelDownloadStartWorker.java",
        };
        for (String path : downloadEntryPoints) {
            String source = source(path);
            assertTrue("download entry point must use its domain runtime contract: " + path,
                    source.contains("DownloadRuntimeProvider"));
            assertFalse("download must not import Host implementation: " + path,
                    source.contains("com.matrix.agent.host."));
        }
        String voiceOwner = source("com/matrix/agent/voice/system/SystemVoiceRuntimeOwner.java");
        assertTrue("voice owner must use its domain runtime contract",
                voiceOwner.contains("VoiceRuntimeProvider"));
        assertFalse("voice system entry must not import Host implementation",
                voiceOwner.contains("com.matrix.agent.host."));
    }

    @Test
    public void modelProtocolClientDoesNotOwnHttpConnectionLifecycle() throws IOException {
        String source = source("com/matrix/agent/model/ModelApiClient.java");
        assertTrue("ModelApiClient must delegate to transport", source.contains("httpTransport.post"));
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
    public void productionNetworkCodeUsesTheHostControlledOkHttpBoundary() throws IOException {
        String[] paths = {
                "com/matrix/agent/model/JsonHttpTransport.java",
                "com/matrix/agent/download/TrustedHttpsJson.java",
                "com/matrix/agent/download/ModelDownloadManager.java",
                "com/matrix/agent/voice/VoskModelDownloader.java",
        };
        for (String path : paths) {
            String source = source(path);
            assertFalse("legacy HttpURLConnection must not return: " + path,
                    source.contains("HttpURLConnection"));
            assertTrue("network boundary must use OkHttp: " + path,
                    source.contains("okhttp3."));
        }
        String metadataTransport = source("com/matrix/agent/download/TrustedHttpsJson.java");
        assertFalse("metadata transport must not create a hidden process client",
                metadataTransport.contains("DEFAULT_CLIENT"));
        String apiClient = source("com/matrix/agent/model/ModelApiClient.java");
        assertFalse("ModelApiClient production construction requires injection",
                apiClient.contains("public ModelApiClient()"));
    }

    @Test
    public void modelDownloadWorkIsDurableAdmissionNotAHiddenDownloader() throws IOException {
        String scheduler = source("com/matrix/agent/download/ModelDownloadWorkScheduler.java");
        String worker = source("com/matrix/agent/download/ModelDownloadStartWorker.java");
        String binder = source("com/matrix/agent/host/rpc/DownloadServiceStub.java");
        String foregroundService = source("com/matrix/agent/download/DownloadService.java");

        assertTrue("download admission must be represented by durable unique work",
                scheduler.contains("enqueueUniqueWork"));
        assertTrue("scheduler must admit the dedicated start worker",
                scheduler.contains("new OneTimeWorkRequest.Builder(ModelDownloadStartWorker.class)"));
        assertTrue("admission must wait for usable network", scheduler.contains("NetworkType.CONNECTED"));
        assertTrue("admission must respect low-storage protection",
                scheduler.contains("setRequiresStorageNotLow(true)"));
        assertTrue("the worker may only launch the visible transfer service",
                worker.contains("DownloadService.start("));
        assertFalse("WorkManager must never own byte transfer", worker.contains("manager.download("));
        assertFalse("WorkManager must never own an HTTP client", worker.contains("okhttp3."));
        assertTrue("Binder download requests must enter the durable admission path",
                binder.contains("workScheduler.enqueue("));
        assertFalse("Binder must not bypass WorkManager to start the FGS",
                binder.contains("DownloadService.start("));
        assertTrue("only DownloadService owns foreground-service startup",
                foregroundService.contains("ContextCompat.startForegroundService"));
    }

    @Test
    public void appContainerDelegatesTaskAssemblyToRuntimeGraph() throws IOException {
        String source = source("com/matrix/agent/host/di/AppContainer.java");
        assertTrue("task assembly belongs to TaskRuntimeGraph",
                source.contains("new TaskRuntimeGraph(taskDependencies)"));
        assertFalse("AppContainer must not mutate a partly-built Engine",
                source.contains("engine.set"));
    }

    @Test
    public void appContainerDelegatesEncryptedMemoryAssemblyToMemoryGraph() throws IOException {
        String source = source("com/matrix/agent/host/di/AppContainer.java");
        assertTrue("memory assembly belongs to MemoryRuntimeGraph",
                source.contains("new MemoryRuntimeGraph("));
        assertFalse("Room writer construction must not leak back into AppContainer",
                source.contains("new com.matrix.agent.data.memory.RoomMemoryWriter("));
        assertFalse("preference migration belongs to MemoryRuntimeGraph",
                source.contains("RoomMemoryMigrator.fromSharedPreferences"));
    }

    @Test
    public void appContainerDelegatesModelAndAuditAssemblyToDedicatedGraphs() throws IOException {
        String source = source("com/matrix/agent/host/di/AppContainer.java");
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

    @Test
    public void modelAndTaskDoNotDependOnEachOthersImplementations() throws IOException {
        String[] modelSources = {
                "com/matrix/agent/model/LlmModelGateway.java",
                "com/matrix/agent/model/LlmPlanner.java",
                "com/matrix/agent/model/ModelGatewayRepository.java",
                "com/matrix/agent/model/JsonHttpTransport.java",
                "com/matrix/agent/model/OpenAiToolProtocol.java",
        };
        for (String path : modelSources) {
            assertFalse("model must consume contracts, not task implementations: " + path,
                    source(path).contains("import com.matrix.agent.task."));
        }
        String[] taskSources = {
                "com/matrix/agent/task/ModelRuntimeCoordinator.java",
                "com/matrix/agent/task/compress/LlmSummaryProvider.java",
        };
        for (String path : taskSources) {
            assertFalse("task must consume contracts, not model implementations: " + path,
                    source(path).contains("import com.matrix.agent.model."));
        }
    }

    @Test
    public void modelReceivesTheTaskProjectedToolList() throws IOException {
        String gateway = source("com/matrix/agent/model/LlmModelGateway.java");
        String planner = source("com/matrix/agent/model/LlmPlanner.java");
        assertTrue("gateway must use the tools carried by the turn contract",
                gateway.contains("request.getTools()"));
        assertFalse("gateway must not re-project from the task registry",
                gateway.contains("CapabilityRegistry"));
        assertTrue("compatibility planner accepts the caller-projected tool list",
                planner.contains("List<ToolDefinition> tools"));
    }

    @Test
    public void hostAndTaskPhysicalBoundariesRemainExplicit() throws IOException {
        String container = source("com/matrix/agent/host/di/AppContainer.java");
        String binder = source("com/matrix/agent/host/rpc/ModelServiceStub.java");
        String durable = source("com/matrix/agent/task/durable/PersistentTaskManager.java");
        assertTrue("composition belongs to host/di", container.contains("class AppContainer"));
        assertTrue("Binder facade belongs to host/rpc", binder.contains("IModelService.Stub"));
        assertTrue("durable lifecycle belongs to task/durable", durable.contains("class PersistentTaskManager"));
        assertFalse("host DI must not contain Binder implementations",
                container.contains("IModelService.Stub"));
    }

    @Test
    public void platformAndDomainLeavesDoNotRegainTaskOrHostImports() throws IOException {
        String[] leaves = {
                "com/matrix/agent/platform/MatrixExecutorRegistry.java",
                "com/matrix/agent/platform/AuditDigest.java",
                "com/matrix/agent/identity/AgentRequest.java",
                "com/matrix/agent/intent/ClassifierFactory.java",
        };
        for (String path : leaves) {
            String text = source(path);
            assertFalse("leaf must not import task implementation: " + path,
                    text.contains("import com.matrix.agent.task."));
            assertFalse("leaf must not import host implementation: " + path,
                    text.contains("import com.matrix.agent.host."));
        }
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
