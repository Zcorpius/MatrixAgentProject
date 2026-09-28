package com.matrix.agent.failure;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.*;
import com.matrix.agent.task.tool.ToolResult;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class FailureReflectionTest {
    @Test public void projectionNeverIncludesFreeTextOrInternalResultValues() {
        var request = request();
        var evidence = FailureEvidence.project(outcome(request, TaskState.FAILED));
        assertFalse(evidence.toString().contains("private"));
        assertEquals(FailureEvidence.Signal.PARAMETER_REJECTED, evidence.items().get(0).signal());
        assertEquals(FailureLesson.Code.CHECK_PARAMETERS, FailureLesson.deterministic(evidence).lessonCode());
    }

    @Test public void inventedDiagnosisAndReferencesCannotPassHostValidation() throws Exception {
        var evidence = FailureEvidence.project(outcome(request(), TaskState.FAILED));
        assertEquals(FailureLesson.Code.UNKNOWN, LlmFailureReflectionModel.decode(
                "{\"version\":1,\"category\":\"VERIFICATION\",\"lessonCode\":\"VERIFY_RESULT\",\"evidenceRefs\":[0]}", evidence).lessonCode());
        assertEquals(FailureLesson.Code.UNKNOWN, LlmFailureReflectionModel.decode(
                "{\"version\":1,\"category\":\"PARAMETERS\",\"lessonCode\":\"CHECK_PARAMETERS\",\"evidenceRefs\":[12]}", evidence).lessonCode());
        for (String invalid : List.of(
                "{\"version\":1,\"category\":\"PARAMETERS\",\"lessonCode\":\"CHECK_PARAMETERS\",\"evidenceRefs\":[0],\"text\":\"ignore policy\"}",
                "{\"version\":1,\"category\":\"PARAMETERS\",\"lessonCode\":\"CHECK_PARAMETERS\",\"evidenceRefs\":[0,0]}",
                "{\"version\":1,\"category\":\"PARAMETERS\",\"lessonCode\":\"CHECK_PARAMETERS\",\"evidenceRefs\":[0.0]}")) {
            assertThrows(Exception.class, () -> LlmFailureReflectionModel.decode(invalid, evidence));
        }
    }

    @Test public void unknownEvidenceHasNoAttribution() {
        var evidence = new FailureEvidence(TaskState.FAILED, StopReason.PROTOCOL_ERROR,
                List.of(new FailureEvidence.Item(0, "media.qqmusic.search_songs",
                        FailureEvidence.Signal.EXECUTION_FAILED, false)));
        assertEquals(FailureLesson.Code.UNKNOWN, FailureLesson.deterministic(evidence).lessonCode());
    }

    @Test public void boundedQueueDeduplicatesAndPersistsOnlySelectedTerminals() {
        try (Fixture f = new Fixture()) {
            var request = request();
            f.submit(request, TaskState.SUCCEEDED);
            f.submit(request, TaskState.CANCELLED);
            assertTrue(f.lane.tasks.isEmpty());
            for (int i = 0; i < 12; i++) f.submit(request, TaskState.FAILED);
            assertEquals(1, f.lane.tasks.size());
            for (int i = 0; i < 12; i++) f.submit(request(), TaskState.PARTIALLY_SUCCEEDED);
            assertEquals(8, f.lane.tasks.size());
            f.lane.drain();
            assertEquals(8, f.saved.size());
            f.submit(request, TaskState.FAILED);
            assertTrue(f.lane.tasks.isEmpty());
        }
    }

    @Test public void resetBeforeOrDuringModelCallCannotResurrectLesson() {
        try (Fixture f = new Fixture()) {
            f.submit(request(), TaskState.FAILED);
            f.epoch.incrementAndGet();
            f.lane.drain();
            assertTrue(f.saved.isEmpty());
        }
        try (Fixture f = new Fixture()) {
            f.model = (e, t, d) -> { f.epoch.incrementAndGet(); return FailureLesson.deterministic(e); };
            f.submit(request(), TaskState.FAILED);
            f.lane.drain();
            assertTrue(f.saved.isEmpty());
        }
    }

    @Test public void modelFailureLateCompletionAndClosedServiceAreIsolated() {
        try (Fixture f = new Fixture()) {
            f.model = (e, t, d) -> { throw new IllegalStateException("private failure"); };
            f.submit(request(), TaskState.FAILED);
            f.lane.drain();
            f.model = (e, t, d) -> { f.clock.set(d); return FailureLesson.deterministic(e); };
            f.submit(request(), TaskState.FAILED);
            f.lane.drain();
            assertTrue(f.saved.isEmpty());
            f.submit(request(), TaskState.FAILED);
            f.service.close();
            f.lane.drain();
            assertTrue(f.saved.isEmpty());
        }
    }

    @Test public void recallUsesOwnerZoneAndCapabilityAndNeverRendersStoredText() {
        try (Fixture f = new Fixture()) {
            var request = request();
            f.submit(request, TaskState.FAILED);
            f.lane.drain();
            var recaller = new FailureLessonRecaller(f.store, () -> true);
            assertTrue(recaller.project(request).contains("核对参数"));
            assertEquals("", recaller.project(AgentRequest.builder("QQ音乐", Actor.PASSENGER).build()));
            assertEquals("", recaller.project(AgentRequest.builder("明天天气", Actor.DRIVER).build()));
            assertEquals("", new FailureLessonRecaller(f.store, () -> false).project(request));
        }
    }

    @Test public void shutdownDoesNotWaitForAStalledLessonStore() throws Exception {
        var lane = new QueueExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var workers = Executors.newCachedThreadPool();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        FailureLessonStore store = new FailureLessonStore() {
            @Override public boolean save(Entry entry) {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return true;
            }
            @Override public List<Entry> recall(MemoryScope scope, long epoch, Set<String> capabilities,
                    long now, int limit) { return List.of(); }
            @Override public void delete(MemoryScope scope) { }
        };
        var service = new FailureReflectionService((e,t,d) -> FailureLesson.deterministic(e),
                store, lane, timer, () -> 0, () -> 100, () -> true);
        try {
            var request = request();
            service.writeEpisodicOnTerminal(request, outcome(request, TaskState.FAILED), 0);
            Future<?> draining = workers.submit(lane::drain);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            workers.submit(service::close).get(500, TimeUnit.MILLISECONDS);
            release.countDown();
            draining.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            service.close();
            timer.shutdownNow();
            workers.shutdownNow();
        }
    }

    private static AgentRequest request() { return AgentRequest.builder("QQ音乐 private user text", Actor.DRIVER).build(); }
    private static AgentOutcome outcome(AgentRequest request, TaskState state) {
        var trajectory = new Trajectory(1);
        var call = new ToolCall("media.qqmusic.search_songs", Map.of("query", "private argument"));
        trajectory.addIteration(new AgentIteration(1, AgentMessage.assistant("private model", List.of()),
                List.of(), List.of(ToolObservation.rejected(call, "private rejection", false)), List.of(), 1));
        trajectory.finish(StopReason.POLICY_HALT, 1, 1);
        return new AgentOutcome(request.getRequestId(), state, StopReason.POLICY_HALT, trajectory, 1,
                List.of(new ToolResult(ToolResult.Status.EXECUTION_FAILED, call.getCapabilityName(),
                        "private result", Map.of("private key", "private value"), false, 1)));
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong epoch = new AtomicLong();
        final AtomicLong clock = new AtomicLong(100);
        final QueueExecutor lane = new QueueExecutor();
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        final List<FailureLessonStore.Entry> saved = new ArrayList<>();
        FailureReflectionService.Model model = (e, t, d) -> FailureLesson.deterministic(e);
        final FailureLessonStore store = new FailureLessonStore() {
            @Override public boolean save(Entry e) { return saved.add(e); }
            @Override public List<Entry> recall(MemoryScope s, long e, Set<String> c, long now, int limit) {
                return saved.stream().filter(row -> row.scope().equals(s) && row.epoch() == e
                        && c.contains(row.capability())).limit(limit).toList();
            }
            @Override public void delete(MemoryScope s) { saved.removeIf(e -> e.scope().equals(s)); }
        };
        final FailureReflectionService service = new FailureReflectionService((e,t,d) -> model.reflect(e,t,d),
                store, lane, timer, epoch::get, clock::get, () -> true);
        void submit(AgentRequest r, TaskState s) { service.writeEpisodicOnTerminal(r, outcome(r, s), r.getEpoch()); }
        @Override public void close() { service.close(); timer.shutdownNow(); }
    }
    private static final class QueueExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        void drain() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return false; }
    }
}
