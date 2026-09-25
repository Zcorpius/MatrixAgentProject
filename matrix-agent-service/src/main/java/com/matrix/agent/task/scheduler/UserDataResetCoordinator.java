package com.matrix.agent.task.scheduler;

import com.matrix.agent.task.steer.Steer;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.*;

import android.util.Log;

import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.audit.ClearOutcome;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.identity.ActorUsers;

/** 用户数据清理事务的协调器；清理顺序和失败语义在此处唯一维护。 */
public final class UserDataResetCoordinator {
    private static final String TAG = "MatrixAgent";
    private static final String DRIVER_USER = ActorUsers.USER_DRIVER;
    private static final String PASSENGER_USER = ActorUsers.USER_PASSENGER;
    private static final String GLOBAL_USER = ActorUsers.USER_GLOBAL;

    private final MemoryStore memoryStore;
    private final SessionManager sessionManager;
    private final AuditRepository auditRepository;
    private final InFlightTaskRegistry inFlightTasks;
    private volatile SteerMailbox steerMailbox;
    private volatile AuditEventRecorder auditEventRecorder = AuditEventRecorder.NOOP;
    /** 对话表清理（clearUserData 覆盖范围）；null 时记 warn 不阻塞主流程。 */
    private volatile Runnable conversationClearHook;
    private volatile java.util.function.BooleanSupplier legacyMemoryClearHook;
    private volatile Runnable resetBeginHook;
    private volatile Runnable resetCompleteHook;

    public UserDataResetCoordinator(MemoryStore memoryStore, SessionManager sessionManager,
            AuditRepository auditRepository, InFlightTaskRegistry inFlightTasks) {
        this.memoryStore = memoryStore;
        this.sessionManager = sessionManager;
        this.auditRepository = auditRepository;
        this.inFlightTasks = inFlightTasks;
    }

    public void setSteerMailbox(SteerMailbox value) { steerMailbox = value; }
    public void setAuditEventRecorder(AuditEventRecorder value) {
        auditEventRecorder = value == null ? AuditEventRecorder.NOOP : value;
    }
    public void setConversationClearHook(Runnable hook) {
        conversationClearHook = hook;
    }
    public void setLegacyMemoryClearHook(java.util.function.BooleanSupplier hook) {
        legacyMemoryClearHook = hook;
    }
    public void setResetLifecycleHooks(Runnable begin, Runnable complete) {
        resetBeginHook = begin;
        resetCompleteHook = complete;
    }
    public synchronized void addConversationClearHook(Runnable hook) {
        if (hook == null) return;
        Runnable previous = conversationClearHook;
        conversationClearHook = previous == null ? hook : () -> {
            previous.run();
            hook.run();
        };
    }
    public boolean offerSteer(String sessionId, Steer steer) {
        SteerMailbox mailbox = steerMailbox;
        if (mailbox == null) {
            // 确认式（评估 v1.0 §4.3）：队列不可用不再抛异常——调用方把附属输入
            // 自收敛 FAILED，宿主执行不受影响。
            Log.w(TAG, "[Reset] steer mailbox 不可用，投递拒绝 session=" + sessionId);
            return false;
        }
        mailbox.offer(sessionId, steer);
        return true;
    }

    public ClearUserDataOutcome clear() {
        Runnable beginHook = resetBeginHook;
        if (beginHook != null) beginHook.run();
        ClearOutcome unattempted = ClearOutcome.notApplicable();
        ClearUserDataOutcome.DomainStatus pending = beginHook == null
                ? ClearUserDataOutcome.DomainStatus.FAILED
                : ClearUserDataOutcome.DomainStatus.PENDING;
        if (memoryStore == null) {
            return new ClearUserDataOutcome(unattempted, unattempted, unattempted,
                    pending, pending, pending, pending, pending);
        }
        Log.i(TAG, "[Reset] begin activeTasks=" + inFlightTasks.size());
        inFlightTasks.cancelAll();
        long epoch;
        try {
            epoch = memoryStore.clearUsersAndBump(
                    ActorUsers.allKnownUserIds());
        } catch (RuntimeException failure) {
            Log.w(TAG, "[Reset] memory clear incomplete cause="
                    + failure.getClass().getSimpleName());
            return new ClearUserDataOutcome(unattempted, unattempted, unattempted,
                    pending, pending, pending, pending, pending);
        }
        java.util.function.BooleanSupplier legacyHook = legacyMemoryClearHook;
        try {
            if (legacyHook != null && !legacyHook.getAsBoolean()) {
                throw new IllegalStateException("legacy memory source could not be cleared");
            }
        } catch (RuntimeException failure) {
            return new ClearUserDataOutcome(unattempted, unattempted, unattempted,
                    ClearUserDataOutcome.DomainStatus.CLEARED,
                    ClearUserDataOutcome.DomainStatus.FAILED, pending, pending, pending);
        }
        AuditEventRecorder recorder = auditEventRecorder;
        SteerMailbox mailbox = steerMailbox;
        try {
            recorder.advanceEpoch(epoch);
            if (mailbox != null) mailbox.advanceEpoch(epoch);
            inFlightTasks.awaitDrain(1_000L);
            if (mailbox != null) mailbox.clearAll();
            sessionManager.clear();
            Runnable conversationHook = conversationClearHook;
            if (conversationHook != null) {
                conversationHook.run();
            } else if (legacyHook != null) {
                throw new IllegalStateException("conversation clear hook unavailable");
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "[Reset] conversation/session clear incomplete cause="
                    + failure.getClass().getSimpleName());
            return new ClearUserDataOutcome(unattempted, unattempted, unattempted,
                    ClearUserDataOutcome.DomainStatus.CLEARED,
                    ClearUserDataOutcome.DomainStatus.CLEARED,
                    ClearUserDataOutcome.DomainStatus.FAILED, pending, pending);
        }
        try {
            recorder.dropByUserZone(DRIVER_USER, "DRIVER", epoch);
            recorder.dropByUserZone(PASSENGER_USER, "PASSENGER", epoch);
            recorder.dropByUserZone(GLOBAL_USER, "GLOBAL", epoch);
        } catch (RuntimeException failure) {
            Log.w(TAG, "[Reset] queued audit clear incomplete cause="
                    + failure.getClass().getSimpleName());
            return new ClearUserDataOutcome(unattempted, unattempted, unattempted,
                    ClearUserDataOutcome.DomainStatus.CLEARED,
                    ClearUserDataOutcome.DomainStatus.CLEARED,
                    ClearUserDataOutcome.DomainStatus.CLEARED,
                    ClearUserDataOutcome.DomainStatus.FAILED, pending);
        }
        ClearUserDataOutcome outcome = new ClearUserDataOutcome(clearAudit(DRIVER_USER, "DRIVER"),
                clearAudit(PASSENGER_USER, "PASSENGER"), clearAudit(GLOBAL_USER, "GLOBAL"),
                ClearUserDataOutcome.DomainStatus.CLEARED,
                ClearUserDataOutcome.DomainStatus.CLEARED,
                ClearUserDataOutcome.DomainStatus.CLEARED,
                ClearUserDataOutcome.DomainStatus.PENDING, pending);
        ClearUserDataOutcome.DomainStatus auditStatus = outcome.getDriverAudit().isFailure()
                || outcome.getPassengerAudit().isFailure() || outcome.getGlobalAudit().isFailure()
                ? ClearUserDataOutcome.DomainStatus.FAILED
                : ClearUserDataOutcome.DomainStatus.CLEARED;
        ClearUserDataOutcome.DomainStatus markerStatus = pending;
        if (auditStatus == ClearUserDataOutcome.DomainStatus.CLEARED) {
            try {
                if (resetCompleteHook != null) resetCompleteHook.run();
                markerStatus = ClearUserDataOutcome.DomainStatus.CLEARED;
            } catch (RuntimeException failure) {
                Log.w(TAG, "[Reset] completion marker pending cause="
                        + failure.getClass().getSimpleName());
            }
        }
        return new ClearUserDataOutcome(outcome.getDriverAudit(), outcome.getPassengerAudit(),
                outcome.getGlobalAudit(), ClearUserDataOutcome.DomainStatus.CLEARED,
                ClearUserDataOutcome.DomainStatus.CLEARED,
                ClearUserDataOutcome.DomainStatus.CLEARED, auditStatus, markerStatus);
    }

    private ClearOutcome clearAudit(String userId, String zone) {
        try {
            ClearOutcome outcome = auditRepository.clearByUserZone(userId, zone);
            return outcome == null ? ClearOutcome.failure("clearByUserZone returned null") : outcome;
        } catch (Throwable error) {
            Log.w(TAG, "[Reset] audit clear failed user=" + userId + " cause="
                    + error.getClass().getSimpleName());
            return ClearOutcome.failure(error.getClass().getSimpleName());
        }
    }
}
