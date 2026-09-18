package com.matrix.agent.task.scheduler;

import com.matrix.agent.task.redact.SafeLog;
import com.matrix.agent.task.*;

import android.util.Log;

import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.intent.MemoryIntentDetector;
import com.matrix.agent.vehicle.VehicleStateSource;
import com.matrix.agent.identity.VehicleZone;

/** 将 Host 已验证的输入派生成可调度的 {@link AgentRequest}。 */
public final class TaskRequestFactory {
    private static final String TAG = "MatrixAgent";

    private final MemoryStore memoryStore;
    private final AgentBudget budget;
    private final VehicleStateSource vehicleStateSource;
    private volatile IntentClassifier intentClassifier;
    private volatile MemoryIntentDetector memoryIntentDetector = MemoryIntentDetector.NOOP;

    public TaskRequestFactory(MemoryStore memoryStore, AgentBudget budget,
            VehicleStateSource vehicleStateSource, IntentClassifier intentClassifier) {
        if (vehicleStateSource == null) throw new IllegalArgumentException("vehicleStateSource 不能为空");
        this.memoryStore = memoryStore;
        this.budget = budget == null ? new AgentBudget() : budget;
        this.vehicleStateSource = vehicleStateSource;
        this.intentClassifier = intentClassifier == null
                ? KeywordIntentClassifier.INSTANCE : intentClassifier;
    }

    public AgentRequest.Builder newRequestBuilder(String command, Actor actor,
            CancellationToken token, String sessionId, String arbitrationKey) {
        boolean intentReadOnly = intentClassifier.isReadOnly(command);
        boolean memorySaveAllowed = memoryIntentDetector.isExplicitMemorySave(command);
        VehicleZone zone = actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER;
        // 部分仅验证 Voice runtime 生命周期的 JVM 测试没有持久化层；旧 Repository
        // 在未派生真实任务前也允许该装配。正式 Host 始终提供 MemoryStore，缺失时仅
        // 使用 epoch=0 的兼容默认值，不能伪造或访问任何用户记忆。
        long capturedEpoch = memoryStore == null ? 0L : memoryStore.currentEpoch();
        Log.i(TAG, "[RequestFactory] command=" + SafeLog.USER_INPUT_PLACEHOLDER
                + " commandChars=" + (command == null ? 0 : command.length())
                + " actor=" + actor + " session=" + sessionId
                + " arbitration=" + arbitrationKey + " intentReadOnly=" + intentReadOnly
                + " memSave=" + memorySaveAllowed
                + " budgetDeadlineMs=" + budget.getTotalDeadlineMillis());
        return AgentRequest.builder(command, actor)
                .sessionId(sessionId)
                .arbitrationKey(arbitrationKey)
                .occupantZone(zone)
                .timeoutMillis(budget.getTotalDeadlineMillis())
                .cancellationToken(token)
                .vehicleState(vehicleStateSource.snapshot())
                .readOnlyHint(intentReadOnly)
                .epoch(capturedEpoch)
                .memorySaveAllowed(memorySaveAllowed);
    }

    public void setIntentClassifier(IntentClassifier classifier) {
        intentClassifier = classifier == null ? KeywordIntentClassifier.INSTANCE : classifier;
    }

    public void setMemoryIntentDetector(MemoryIntentDetector detector) {
        memoryIntentDetector = detector == null ? MemoryIntentDetector.NOOP : detector;
    }
}
