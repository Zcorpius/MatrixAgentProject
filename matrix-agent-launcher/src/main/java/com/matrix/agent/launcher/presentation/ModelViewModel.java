package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.model.ConnectionTestResult;
import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.launcher.data.ModelRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** ViewModel for model provisioning, runtime status and active-model selection. */
public final class ModelViewModel extends ViewModel {
    private final ModelRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(State.initial());
    private final OperationEpoch operations = new OperationEpoch();
    private State current = State.initial();

    public ModelViewModel(ModelRepository repository) { this.repository = repository; }
    public LiveData<State> state() { return state; }
    public boolean isHostConnected() { return repository.isHostConnected(); }

    public void provision(@NonNull String providerId, @NonNull String modelId,
            @NonNull String endpoint, boolean apiKeyRequired, @NonNull char[] secret) {
        if (apiKeyRequired && secret.length == 0) { update(current.withNotice(Notice.MISSING_KEY, 0)); return; }
        if ("doubao".equals(providerId) && modelId.trim().isEmpty()) {
            Arrays.fill(secret, '\0');
            update(current.withNotice(Notice.MISSING_MODEL_ID, 0));
            return;
        }
        // vLLM/Hugging Face model identifiers legitimately contain a single namespace slash.
        if (!modelId.isEmpty() && !modelId.matches("(?=.{1,120}$)[A-Za-z0-9._:-]+(/[A-Za-z0-9._:-]+)?")) {
            Arrays.fill(secret, '\0');
            update(current.withNotice(Notice.INVALID_MODEL_ID, 0));
            return;
        }
        char[] retainedSecret = secret.clone();
        final boolean credentialProvided = retainedSecret.length > 0;
        Arrays.fill(secret, '\0');
        final long operation = operations.begin();
        update(current.withBusy(true, Notice.PROVISIONING, 0).withSavedForm(null));
        repository.provision(providerId, modelId, endpoint, retainedSecret, code -> {
            if (!operations.isCurrent(operation)) return;
            SavedForm saved = code == 0
                    ? new SavedForm(providerId, modelId, endpoint, credentialProvided) : null;
            update(current.withBusy(false, code == 0 ? Notice.SAVED : Notice.SAVE_FAILED, code)
                    .withSavedForm(saved));
            if (code == 0) refresh(operation);
        }, result -> {
            try {
                if (!operations.isCurrent(operation)) return;
                if (!result.isSuccess() || !Boolean.TRUE.equals(result.value)) {
                    update(current.withBusy(false, Notice.HOST_UNAVAILABLE, 0));
                }
            } finally { Arrays.fill(retainedSecret, '\0'); }
        });
    }

    public void testConnection(@NonNull String providerId) {
        final long operation = operations.begin();
        update(current.withBusy(true, Notice.TESTING, 0));
        repository.test(providerId, result -> {
            if (!operations.isCurrent(operation)) return;
            ConnectionTestResult value = result.value;
            if (!result.isSuccess() || value == null) update(current.withBusy(false, Notice.TEST_FAILED, 0));
            else update(current.withBusy(false, value.success ? Notice.TEST_SUCCEEDED : Notice.TEST_FAILED,
                    value.success ? (int) Math.min(Integer.MAX_VALUE, value.latencyMs) : value.errorCode));
        });
    }

    public void refresh() {
        refresh(operations.current());
    }

    private void refresh(long expectedOperation) {
        repository.snapshot(result -> {
            if (!operations.isCurrent(expectedOperation)) return;
            if (!result.isSuccess() || result.value == null) {
                update(current.withBusy(false, Notice.HOST_UNAVAILABLE, 0));
                return;
            }
            ModelRepository.Snapshot snapshot = result.value;
            update(new State(snapshot.runtime, snapshot.models, false, Notice.RUNTIME, 0,
                    current.savedForm));
        });
    }

    public void select(@NonNull ModelInfo model) {
        final long operation = operations.begin();
        update(current.withBusy(true, Notice.SWITCHING, 0));
        repository.select(model.modelId, code -> {
            if (!operations.isCurrent(operation)) return;
            update(current.withBusy(false, code == 0 ? Notice.SWITCHED : Notice.SWITCH_FAILED, code));
            if (code == 0) refresh(operation);
        }, result -> {
            if (!operations.isCurrent(operation)) return;
            if (!result.isSuccess() || !Boolean.TRUE.equals(result.value)) {
                update(current.withBusy(false, Notice.HOST_UNAVAILABLE, 0));
            }
        });
    }

    private void update(State next) { current = next; state.postValue(next); }

    public enum Notice {
        IDLE, MISSING_KEY, MISSING_MODEL_ID, INVALID_MODEL_ID, PROVISIONING, SAVED, SAVE_FAILED,
        TESTING, TEST_SUCCEEDED, TEST_FAILED,
        RUNTIME, SWITCHING, SWITCHED, SWITCH_FAILED, HOST_UNAVAILABLE
    }

    public static final class State {
        @Nullable public final ModelRuntimeStatus runtime;
        @NonNull public final List<ModelInfo> models;
        public final boolean busy;
        @NonNull public final Notice notice;
        public final int code;
        /** 最近一次成功保存的非敏感表单摘要；保留到下一次保存操作，防止刷新状态吞掉 SAVED 帧。 */
        @Nullable public final SavedForm savedForm;
        State(@Nullable ModelRuntimeStatus runtime, @NonNull List<ModelInfo> models, boolean busy,
                @NonNull Notice notice, int code) {
            this(runtime, models, busy, notice, code, null);
        }
        State(@Nullable ModelRuntimeStatus runtime, @NonNull List<ModelInfo> models, boolean busy,
                @NonNull Notice notice, int code, @Nullable SavedForm savedForm) {
            this.runtime = runtime;
            this.models = Collections.unmodifiableList(new ArrayList<>(models));
            this.busy = busy;
            this.notice = notice;
            this.code = code;
            this.savedForm = savedForm;
        }
        static State initial() { return new State(null, Collections.emptyList(), false, Notice.IDLE, 0); }
        State withBusy(boolean busy, Notice notice, int code) {
            return new State(runtime, models, busy, notice, code, savedForm);
        }
        State withNotice(Notice notice, int code) { return withBusy(busy, notice, code); }
        State withSavedForm(@Nullable SavedForm savedForm) {
            return new State(runtime, models, busy, notice, code, savedForm);
        }
    }

    /** 仅用于 Launcher 回显的非敏感字段；API Key 不会进入此对象。 */
    public static final class SavedForm {
        @NonNull public final String providerId;
        @NonNull public final String modelId;
        @NonNull public final String endpoint;
        public final boolean credentialProvided;

        SavedForm(@NonNull String providerId, @NonNull String modelId, @NonNull String endpoint,
                boolean credentialProvided) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.endpoint = endpoint;
            this.credentialProvided = credentialProvided;
        }
    }
}
