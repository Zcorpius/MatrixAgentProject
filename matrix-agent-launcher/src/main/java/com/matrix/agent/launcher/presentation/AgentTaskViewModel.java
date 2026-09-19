package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentTaskEvent;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.launcher.data.AgentTaskRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns task-page state, SDK calls and callback subscription; the Fragment only renders it. */
public final class AgentTaskViewModel extends ViewModel {
    private final AgentTaskRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(State.initial());
    private final Object lock = new Object();
    private final OperationEpoch operations = new OperationEpoch();
    @Nullable private AutoCloseable subscription;
    @Nullable private AutoCloseable pendingSubmit;
    private State current = State.initial();

    public AgentTaskViewModel(AgentTaskRepository repository) { this.repository = repository; }
    public LiveData<State> state() { return state; }
    public boolean isHostConnected() { return repository.isHostConnected(); }

    public void submit(@NonNull String text) {
        if (text.trim().isEmpty()) { update(current.withNotice(Notice.ENTER_TASK, 0)); return; }
        final long operation = operations.begin();
        closePendingSubmit();
        closeSubscription();
        update(new State(null, 0L, null, Collections.emptyList(), Notice.SUBMITTING, 0));
        pendingSubmit = repository.submit(text.trim(), result -> {
            if (!operations.isCurrent(operation)) return;
            closePendingSubmit();
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(Notice.SUBMIT_FAILED, 0));
                return;
            }
            AgentTaskHandle handle = result.value;
            if (handle.errorCode != 0 || handle.taskId == null) {
                update(current.withNotice(Notice.REJECTED, handle.errorCode));
                return;
            }
            update(new State(handle.taskId, handle.acceptedSequence, null, Collections.emptyList(),
                    Notice.ACCEPTED, handle.state));
            subscribe(operation, handle.taskId);
            refresh(operation, handle.taskId);
        });
    }

    public void refresh() {
        refresh(operations.current(), current.taskId);
    }

    private void refresh(long operation, @Nullable String taskId) {
        if (taskId == null) return;
        repository.snapshot(taskId, result -> {
            if (!operations.isCurrent(operation)) return;
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(Notice.SNAPSHOT_UNAVAILABLE, 0));
                return;
            }
            AgentTaskSnapshot snapshot = result.value;
            synchronized (lock) {
                if (!taskId.equals(current.taskId) || !snapshot.taskId.equals(current.taskId)) return;
                updateLocked(new State(current.taskId, Math.max(current.lastSequence, snapshot.lastSequence),
                        snapshot, current.events, Notice.SNAPSHOT, snapshot.errorCode));
                if (AgentTaskState.isTerminal(snapshot.state)) closeSubscriptionLocked();
            }
        });
    }

    public void cancel() {
        String taskId = current.taskId;
        if (taskId == null || current.isTerminal()) {
            update(current.withNotice(Notice.NO_ACTIVE_TASK, 0));
            return;
        }
        final long operation = operations.begin();
        closePendingSubmit();
        update(current.withNotice(Notice.CANCELLING, 0));
        repository.cancel(taskId, result -> {
            if (!operations.isCurrent(operation) || !taskId.equals(current.taskId)) return;
            AgentOperationResult outcome = result.value;
            update(current.withNotice(Notice.CANCEL_RESULT, outcome == null ? -1 : outcome.code));
            refresh(operation, taskId);
        });
    }

    private void subscribe(long operation, @NonNull String taskId) {
        State snapshot = current;
        if (!taskId.equals(snapshot.taskId)) return;
        repository.subscribe(taskId, snapshot.lastSequence,
                event -> onEvent(operation, taskId, event), result -> {
            if (!operations.isCurrent(operation) || !taskId.equals(current.taskId)) {
                close(result.value);
                return;
            }
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(Notice.SUBSCRIBE_FAILED, 0));
                return;
            }
            synchronized (lock) {
                closeSubscriptionLocked();
                subscription = result.value;
            }
        });
    }

    private void onEvent(long operation, @NonNull String taskId, @Nullable AgentTaskEvent event) {
        if (event == null) return;
        boolean resync = false;
        synchronized (lock) {
            if (!operations.isCurrent(operation) || !taskId.equals(current.taskId)
                    || !event.taskId.equals(current.taskId)) return;
            List<AgentTaskEvent> events = new ArrayList<>(current.events);
            events.add(event);
            if (events.size() > 80) events.remove(0);
            updateLocked(new State(current.taskId, Math.max(current.lastSequence, event.sequence),
                    current.snapshot, events, Notice.EVENT, 0));
            resync = event.type == AgentTaskEvent.TYPE_RESYNC_REQUIRED;
        }
        if (resync) refresh(operation, taskId);
    }

    private void update(@NonNull State next) { synchronized (lock) { updateLocked(next); } }
    private void updateLocked(@NonNull State next) { current = next; state.postValue(next); }
    private void closeSubscription() { synchronized (lock) { closeSubscriptionLocked(); } }
    private void closePendingSubmit() {
        AutoCloseable value = pendingSubmit;
        pendingSubmit = null;
        close(value);
    }
    private void closeSubscriptionLocked() {
        if (subscription == null) return;
        try { subscription.close(); } catch (Exception ignored) { }
        subscription = null;
    }
    private static void close(@Nullable AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) { }
    }
    @Override protected void onCleared() { closePendingSubmit(); closeSubscription(); }

    public enum Notice {
        IDLE, ENTER_TASK, SUBMITTING, SUBMIT_FAILED, REJECTED, ACCEPTED, SNAPSHOT,
        SNAPSHOT_UNAVAILABLE, SUBSCRIBE_FAILED, EVENT, CANCELLING, CANCEL_RESULT, NO_ACTIVE_TASK
    }

    public static final class State {
        @Nullable public final String taskId;
        public final long lastSequence;
        @Nullable public final AgentTaskSnapshot snapshot;
        @NonNull public final List<AgentTaskEvent> events;
        @NonNull public final Notice notice;
        public final int code;
        State(@Nullable String taskId, long lastSequence, @Nullable AgentTaskSnapshot snapshot,
                @NonNull List<AgentTaskEvent> events, @NonNull Notice notice, int code) {
            this.taskId = taskId;
            this.lastSequence = lastSequence;
            this.snapshot = snapshot;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.notice = notice;
            this.code = code;
        }
        static State initial() { return new State(null, 0L, null, Collections.emptyList(), Notice.IDLE, 0); }
        State withNotice(Notice notice, int code) {
            return new State(taskId, lastSequence, snapshot, events, notice, code);
        }
        /** 任务快照可能还未回补；此时也要以最新状态事件关闭终态操作入口。 */
        boolean isTerminal() {
            if (snapshot != null) return AgentTaskState.isTerminal(snapshot.state);
            for (int index = events.size() - 1; index >= 0; index--) {
                if (AgentTaskState.isTerminal(events.get(index).state)) return true;
            }
            return false;
        }
    }
}
