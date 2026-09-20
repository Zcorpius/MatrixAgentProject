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

    private final MemoryStore memoryStore;
    private final SessionManager sessionManager;
    private final AuditRepository auditRepository;
    private final InFlightTaskRegistry inFlightTasks;
    private volatile SteerMailbox steerMailbox;
    private volatile AuditEventRecorder auditEventRecorder = AuditEventRecorder.NOOP;
    /** 对话表清理（clearUserData 覆盖范围）；null 时记 warn 不阻塞主流程。 */
    private volatile Runnable conversationClearHook;

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
    public synchronized void addConversationClearHook(Runnable hook) {
        if (hook == null) return;
        Runnable previous = conversationClearHook;
        conversationClearHook = previous == null ? hook : () -> {
            previous.run();
            hook.run();
        };
    }
    public void offerSteer(String sessionId, Steer steer) {
        SteerMailbox mailbox = steerMailbox;
        if (mailbox == null) throw new IllegalStateException("steer mailbox unavailable");
        mailbox.offer(sessionId, steer);
    }

    public ClearUserDataOutcome clear() {
        if (memoryStore == null) throw new IllegalStateException("memory store unavailable");
        Log.i(TAG, "[Reset] begin activeTasks=" + inFlightTasks.size());
        inFlightTasks.cancelAll();
        long epoch = memoryStore.clearUserDataAndBump(DRIVER_USER, PASSENGER_USER);
        AuditEventRecorder recorder = auditEventRecorder;
        try { recorder.advanceEpoch(epoch); } catch (Throwable ignored) { }
        SteerMailbox mailbox = steerMailbox;
        if (mailbox != null) mailbox.advanceEpoch(epoch);
        inFlightTasks.awaitDrain(1_000L);
        if (mailbox != null) mailbox.clearAll();
        sessionManager.clear();
        // 对话正文与 link 属于同级用户数据。钩子失败不能伪装成完整清理：主流程无法回滚
        // 已清 memory，但必须向调用者暴露失败，避免敏感对话悄然残留。
        Runnable conversationHook = conversationClearHook;
        if (conversationHook != null) {
            conversationHook.run();
        } else {
            Log.w(TAG, "[Reset] conversation clear hook unavailable");
        }
        try {
            recorder.dropByUserZone(DRIVER_USER, "DRIVER", epoch);
            recorder.dropByUserZone(PASSENGER_USER, "PASSENGER", epoch);
        } catch (Throwable ignored) { }
        return new ClearUserDataOutcome(clearAudit(DRIVER_USER, "DRIVER"),
                clearAudit(PASSENGER_USER, "PASSENGER"));
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
