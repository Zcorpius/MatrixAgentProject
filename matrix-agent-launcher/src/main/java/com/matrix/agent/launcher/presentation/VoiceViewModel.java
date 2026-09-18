package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.launcher.data.VoiceRepository;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Owns the short-lived voice-session callback and converts SDK states into presentation states.
 * The Fragment only renders these values, so it never owns a Binder subscription or Manager.
 */
public final class VoiceViewModel extends ViewModel {
    private final VoiceRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(State.initial());
    private final OperationEpoch operations = new OperationEpoch();
    @Nullable private AutoCloseable statusSubscription;
    @Nullable private ScheduledFuture<?> modelPolling;
    private boolean statusSubscriptionPending;
    private boolean modelRefreshInFlight;
    private boolean cleared;
    private State current = State.initial();

    public VoiceViewModel(VoiceRepository repository) { this.repository = repository; }

    public LiveData<State> state() { return state; }
    public boolean isHostConnected() { return repository.isHostConnected(); }

    /** Idempotently begins the service-status subscription for this activity-scoped ViewModel. */
    public void startObserving() {
        if (cleared || statusSubscription != null || statusSubscriptionPending) return;
        statusSubscriptionPending = true;
        repository.subscribeStatus(this::onServiceStatus, result -> {
            statusSubscriptionPending = false;
            if (cleared) {
                close(result.value);
                return;
            }
            if (!result.isSuccess() || result.value == null) {
                update(current.withPhase(Phase.HOST_UNAVAILABLE, MatrixErrorCode.SERVICE_NOT_READY));
                return;
            }
            if (statusSubscription != null) close(result.value);
            else statusSubscription = result.value;
        });
        refresh();
        if (modelPolling == null) modelPolling = repository.scheduleModelRefresh(this::refreshModels);
    }

    public void refresh() {
        repository.status(result -> {
            if (!result.isSuccess() || result.value == null) {
                if (current.sessionId == null) {
                    update(current.withPhase(Phase.HOST_UNAVAILABLE, MatrixErrorCode.SERVICE_NOT_READY));
                }
                return;
            }
            onServiceStatus(result.value);
        });
        refreshModels();
    }

    public void refreshModels() {
        if (cleared || modelRefreshInFlight) return;
        modelRefreshInFlight = true;
        repository.offlineModels(result -> {
            modelRefreshInFlight = false;
            if (cleared || !result.isSuccess() || result.value == null) return;
            update(current.withModels(result.value));
        });
    }

    public void deleteOfflineModel(@NonNull String modelId) {
        if (current.deletingModelId != null) return;
        update(current.withDeletingModel(modelId));
        repository.deleteOfflineModel(modelId, UUID.randomUUID().toString(), result -> {
            if (cleared) return;
            VoiceOperationResult outcome = result.value;
            if (!result.isSuccess() || outcome == null || outcome.code != MatrixErrorCode.SUCCESS) {
                update(current.withDeletingModel(null).withModelOperationError(
                        outcome == null ? MatrixErrorCode.SERVICE_NOT_READY : outcome.code));
                return;
            }
            update(current.withDeletingModel(null).withModelOperationError(MatrixErrorCode.SUCCESS));
            refreshModels();
        });
    }

    public void installOfflineModels() {
        repository.installOfflineModels(UUID.randomUUID().toString(), result -> {
            if (cleared) return;
            VoiceOperationResult outcome = result.value;
            if (!result.isSuccess() || outcome == null || outcome.code != MatrixErrorCode.SUCCESS) {
                update(current.withModelOperationError(
                        outcome == null ? MatrixErrorCode.SERVICE_NOT_READY : outcome.code));
                return;
            }
            update(current.withModelOperationError(MatrixErrorCode.SUCCESS));
            refreshModels();
        });
    }

    public void startSession(@NonNull String languageTag) {
        if (current.isActive()) return;
        final long operation = operations.begin();
        update(new State(null, Phase.WAKING, 0, current.finalText, "", current.enabled,
                current.models, current.deletingModelId, current.modelOperationError));
        VoiceSessionRequest request = new VoiceSessionRequest(VoiceSessionRequest.TRIGGER_PTT, languageTag);
        repository.start(request, UUID.randomUUID().toString(), listenerFor(operation), result -> {
            if (!operations.isCurrent(operation)) return;
            VoiceSessionHandle handle = result.value;
            if (!result.isSuccess() || handle == null || handle.sessionId == null) {
                update(current.withPhase(Phase.ERROR, MatrixErrorCode.SERVICE_NOT_READY));
                return;
            }
            // A fast service may have delivered LISTENING before this start result.  Preserve
            // that phase and only bind its session ID here.
            update(current.withSession(handle.sessionId));
        });
    }

    public void finishRecording() {
        String sessionId = current.sessionId;
        if (sessionId == null) return;
        final long operation = operations.current();
        update(current.withPhase(Phase.FINISHING, 0));
        repository.stop(sessionId, UUID.randomUUID().toString(), result -> {
            if (!operations.isCurrent(operation) || !sessionId.equals(current.sessionId)) return;
            VoiceOperationResult outcome = result.value;
            if (!result.isSuccess() || outcome == null || outcome.code != MatrixErrorCode.SUCCESS) {
                update(current.withPhase(Phase.ERROR,
                        outcome == null ? MatrixErrorCode.SERVICE_NOT_READY : outcome.code));
            }
        });
    }

    public void interrupt() {
        if (current.sessionId == null) return;
        final long operation = operations.current();
        update(current.withPhase(Phase.INTERRUPTING, 0));
        repository.interrupt(UUID.randomUUID().toString(), result -> {
            if (!operations.isCurrent(operation)) return;
            VoiceOperationResult outcome = result.value;
            if (!result.isSuccess() || outcome == null || outcome.code != MatrixErrorCode.SUCCESS) {
                update(current.withPhase(Phase.ERROR,
                        outcome == null ? MatrixErrorCode.SERVICE_NOT_READY : outcome.code));
            }
        });
    }

    private VoiceRepository.SessionListener listenerFor(long operation) {
        return new VoiceRepository.SessionListener() {
            @Override public void onSessionStateChanged(String sessionId, int sessionState) {
                if (!accepts(operation, sessionId)) return;
                Phase phase = phaseFor(sessionState);
                boolean terminal = sessionState == VoiceServiceStatus.SESSION_IDLE;
                if (terminal) phase = terminalPhase(current.phase);
                update(new State(terminal ? null : sessionId, phase, 0, current.finalText,
                        current.partialText, current.enabled, current.models,
                        current.deletingModelId, current.modelOperationError));
            }

            @Override public void onPartialText(String sessionId, String text) {
                if (!accepts(operation, sessionId)) return;
                update(new State(sessionId, current.phase, 0, current.finalText,
                        text == null ? "" : text, current.enabled, current.models,
                        current.deletingModelId, current.modelOperationError));
            }

            @Override public void onFinalText(String sessionId, String text) {
                if (!accepts(operation, sessionId)) return;
                String safe = text == null ? "" : text.trim();
                String finalText = safe.isEmpty() ? current.finalText
                        : current.finalText.isEmpty() ? safe : current.finalText + "\n\n" + safe;
                update(new State(sessionId, current.phase, 0, finalText, "", current.enabled,
                        current.models, current.deletingModelId, current.modelOperationError));
            }

            @Override public void onSessionError(String sessionId, int errorCode) {
                if (sessionId != null && !accepts(operation, sessionId)) return;
                update(new State(null, Phase.ERROR, errorCode, current.finalText, current.partialText,
                        current.enabled, current.models, current.deletingModelId,
                        current.modelOperationError));
            }
        };
    }

    private boolean accepts(long operation, @Nullable String sessionId) {
        return operations.isCurrent(operation)
                && (current.sessionId == null || current.sessionId.equals(sessionId));
    }

    private void onServiceStatus(@NonNull VoiceServiceStatus status) {
        if (current.isActive() || isStickyTerminal(current.phase)) {
            update(current.withEnabled(status.enabled));
            return;
        }
        Phase phase = !status.enabled ? Phase.DISABLED : phaseFor(status.sessionState);
        update(new State(null, phase, status.errorCode, current.finalText, current.partialText,
                status.enabled, current.models, current.deletingModelId, current.modelOperationError));
    }

    private static Phase phaseFor(int state) {
        switch (state) {
            case VoiceServiceStatus.SESSION_LISTENING: return Phase.RECORDING;
            case VoiceServiceStatus.SESSION_THINKING: return Phase.WORKING;
            case VoiceServiceStatus.SESSION_SPEAKING: return Phase.RESPONDING;
            default: return Phase.READY;
        }
    }

    /** Host may publish IDLE through both the session callback and the global status stream. */
    static Phase terminalPhase(@NonNull Phase current) {
        if (current == Phase.INTERRUPTING || current == Phase.INTERRUPTED) {
            return Phase.INTERRUPTED;
        }
        if (current == Phase.FINISHING || current == Phase.FINISHED) {
            return Phase.FINISHED;
        }
        return current == Phase.ERROR ? Phase.ERROR : Phase.READY;
    }

    private static boolean isStickyTerminal(@NonNull Phase phase) {
        return phase == Phase.FINISHED || phase == Phase.INTERRUPTED || phase == Phase.ERROR;
    }

    private void update(@NonNull State next) { current = next; state.postValue(next); }

    @Override protected void onCleared() {
        cleared = true;
        close(statusSubscription);
        statusSubscription = null;
        if (modelPolling != null) modelPolling.cancel(false);
        modelPolling = null;
    }

    private static void close(@Nullable AutoCloseable value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) { }
    }

    public enum Phase {
        HOST_UNAVAILABLE, DISABLED, READY, WAKING, RECORDING, FINISHING, WORKING, RESPONDING,
        INTERRUPTING, INTERRUPTED, FINISHED, ERROR
    }

    public static final class State {
        @Nullable public final String sessionId;
        @NonNull public final Phase phase;
        public final int errorCode;
        @NonNull public final String finalText;
        @NonNull public final String partialText;
        public final boolean enabled;
        @NonNull public final List<ModelDownloadInfo> models;
        @Nullable public final String deletingModelId;
        public final int modelOperationError;

        State(@Nullable String sessionId, @NonNull Phase phase, int errorCode,
                @NonNull String finalText, @NonNull String partialText, boolean enabled,
                @NonNull List<ModelDownloadInfo> models, @Nullable String deletingModelId,
                int modelOperationError) {
            this.sessionId = sessionId;
            this.phase = phase;
            this.errorCode = errorCode;
            this.finalText = finalText;
            this.partialText = partialText;
            this.enabled = enabled;
            this.models = Collections.unmodifiableList(new java.util.ArrayList<>(models));
            this.deletingModelId = deletingModelId;
            this.modelOperationError = modelOperationError;
        }

        static State initial() { return new State(null, Phase.HOST_UNAVAILABLE,
                MatrixErrorCode.SERVICE_NOT_READY, "", "", false, Collections.emptyList(), null,
                MatrixErrorCode.SUCCESS); }
        boolean isActive() { return sessionId != null || phase == Phase.WAKING; }
        State withPhase(Phase value, int code) {
            return new State(sessionId, value, code, finalText, partialText, enabled, models,
                    deletingModelId, modelOperationError);
        }
        State withSession(String value) {
            return new State(value, phase, errorCode, finalText, partialText, enabled, models,
                    deletingModelId, modelOperationError);
        }
        State withEnabled(boolean value) {
            return new State(sessionId, phase, errorCode, finalText, partialText, value, models,
                    deletingModelId, modelOperationError);
        }
        State withModels(@NonNull List<ModelDownloadInfo> value) {
            return new State(sessionId, phase, errorCode, finalText, partialText, enabled, value,
                    deletingModelId, modelOperationError);
        }
        State withDeletingModel(@Nullable String value) {
            return new State(sessionId, phase, errorCode, finalText, partialText, enabled, models,
                    value, modelOperationError);
        }
        State withModelOperationError(int value) {
            return new State(sessionId, phase, errorCode, finalText, partialText, enabled, models,
                    deletingModelId, value);
        }
    }
}
