package com.matrix.agent.host.di;

import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.failure.RoomFailureLessonStore;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.failure.FailureLessonRecaller;
import com.matrix.agent.failure.FailureReflectionService;
import com.matrix.agent.failure.LlmFailureReflectionModel;
import com.matrix.agent.platform.MatrixExecutorRegistry;
import com.matrix.agent.task.port.TaskMemoryWriter;
import java.util.function.Supplier;

/** Host owns feature admission and lifecycle; neither task nor memory owns a background executor. */
final class FailureReflectionGraph implements AutoCloseable {
    private final FailureReflectionService service;
    private final FailureLessonRecaller recaller;
    FailureReflectionGraph(MatrixDatabase db, MemoryStore memory, LlmClient client,
            Supplier<ModelConfig> config, MatrixExecutorRegistry executors) {
        if (db == null) { service = null; recaller = null; return; }
        var store = new RoomFailureLessonStore(db);
        java.util.function.BooleanSupplier enabled = () -> com.matrix.agent.BuildConfig.MATRIX_FAILURE_REFLECTION;
        service = new FailureReflectionService(new LlmFailureReflectionModel(client, config), store,
                executors.reflectionExecutor(), executors.timerScheduler(), memory::currentEpoch,
                System::currentTimeMillis, enabled);
        recaller = new FailureLessonRecaller(store, enabled);
    }
    TaskMemoryWriter terminalObserver() { return service == null ? TaskMemoryWriter.NOOP : service; }
    FailureLessonRecaller recaller() { return recaller; }
    @Override public void close() { if (service != null) service.close(); }
}
