package com.matrix.agent.failure;

import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.port.TaskMemoryWriter;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Optional bounded background work. Terminal task completion never waits for the model or DB. */
public final class FailureReflectionService implements TaskMemoryWriter, AutoCloseable {
    public static final long DEADLINE_MILLIS = 8_000;
    public static final int MAX_PENDING = 8;
    @FunctionalInterface public interface Model {
        FailureLesson reflect(FailureEvidence evidence, CancellationToken token, long deadline) throws Exception;
    }
    private record Key(MemoryScope scope, String task, long epoch) { }
    private final Model model;
    private final FailureLessonStore store;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final LongSupplier epoch;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    private final Set<Key> pending = new HashSet<>();
    private final java.util.LinkedHashSet<Key> completed = new java.util.LinkedHashSet<>();
    private final Set<CancellationToken> active = new HashSet<>();
    private boolean closed;

    public FailureReflectionService(Model model, FailureLessonStore store, ExecutorService executor,
            ScheduledExecutorService timer, LongSupplier epoch, LongSupplier clock, BooleanSupplier enabled) {
        this.model = java.util.Objects.requireNonNull(model);
        this.store = java.util.Objects.requireNonNull(store);
        this.executor = java.util.Objects.requireNonNull(executor);
        this.timer = java.util.Objects.requireNonNull(timer);
        this.epoch = java.util.Objects.requireNonNull(epoch);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.enabled = java.util.Objects.requireNonNull(enabled);
    }

    @Override public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
        try {
            if (!enabled.getAsBoolean() || (outcome.getFinalState() != TaskState.FAILED
                    && outcome.getFinalState() != TaskState.PARTIALLY_SUCCEEDED)) return;
            FailureEvidence evidence = FailureEvidence.project(outcome);
            if (evidence.items().isEmpty()) return;
            Key key = new Key(new MemoryScope(ActorUsers.userIdOf(request), request.getOccupantZone()),
                    outcome.getRequestId(), requestEpoch);
            synchronized (this) {
                if (closed || completed.contains(key) || pending.size() >= MAX_PENDING || !pending.add(key)) return;
            }
            try { executor.execute(() -> reflect(key, evidence)); }
            catch (RejectedExecutionException rejected) { synchronized (this) { pending.remove(key); } }
        } catch (RuntimeException ignored) {
            // An optional observer cannot alter the already determined terminal outcome.
        }
    }

    private void reflect(Key key, FailureEvidence evidence) {
        CancellationToken token = new CancellationToken();
        java.util.concurrent.ScheduledFuture<?> timeout = null;
        try {
            synchronized (this) {
                if (closed || epoch.getAsLong() != key.epoch()) return;
                active.add(token);
            }
            long now = clock.getAsLong();
            long deadline = Math.addExact(now, DEADLINE_MILLIS);
            timeout = timer.schedule(token::cancel, DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
            FailureLesson lesson = model.reflect(evidence, token, deadline);
            if (lesson == null || !lesson.groundedIn(evidence)) return;
            FailureLessonStore.Entry entry;
            synchronized (this) {
                if (closed || token.isCancelled() || clock.getAsLong() >= deadline
                        || epoch.getAsLong() != key.epoch()) return;
                // Persist one capability per task. DB uniqueness handles duplicate terminals after completion.
                String capability = evidence.items().get(lesson.evidenceRefs().get(0)).capability();
                entry = new FailureLessonStore.Entry(key.scope(), key.task(), key.epoch(), now,
                        capability, lesson);
            }
            // Room may wait on another writer. Shutdown and terminal dispatch must not inherit that wait.
            if (!token.isCancelled()) store.save(entry);
        } catch (Exception ignored) {
            // No free text (including exception messages) leaves this diagnostic lane.
        } finally {
            if (timeout != null) timeout.cancel(false);
            synchronized (this) {
                active.remove(token);
                pending.remove(key);
                completed.add(key);
                while (completed.size() > 128) completed.remove(completed.iterator().next());
            }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        active.forEach(CancellationToken::cancel);
        active.clear();
        pending.clear();
        completed.clear();
        // Executors belong to the Host registry, not to this optional feature.
    }
}
