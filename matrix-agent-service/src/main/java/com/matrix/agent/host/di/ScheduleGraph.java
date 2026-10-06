package com.matrix.agent.host.di;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.data.schedule.ScheduleOutboxEntity;
import com.matrix.agent.data.schedule.ScheduleRunEntity;
import com.matrix.agent.schedule.ScheduleRuntime;
import com.matrix.agent.schedule.android.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.store.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Process-owned, narrow schedule graph; never obtains AppContainer on the reminder path. */
public final class ScheduleGraph implements ScheduleRuntime {
    private final Context context;
    private final AndroidScheduleClock clock;
    private final ScheduleAlarmAdapter alarms;
    private final ScheduleNotificationPort notifications;
    private final com.matrix.agent.platform.weather.WeatherConfigStore weatherConfig;
    private final ExecutorService lane = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), action -> new Thread(action, "matrix-schedule"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService watchdog = new com.matrix.agent.platform.BoundedScheduledExecutor("matrix-schedule-deadline", 512);
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile ScheduleStore store;
    private volatile String reminderBlock = "";
    private volatile LongExecutionStarter longExecution;
    private final ScheduleCommandFence commandFence = new ScheduleCommandFence();

    public interface LongExecutionStarter { void start(String runId); }
    public ScheduleGraph(Context context) {
        this.context = context.getApplicationContext();
        clock = new AndroidScheduleClock(context); alarms = new ScheduleAlarmAdapter(context);
        notifications = new ScheduleNotificationPort(context);
        weatherConfig = new com.matrix.agent.platform.weather.WeatherConfigStore(context);
    }
    public AndroidScheduleClock clock() { return clock; }
    public ScheduleAlarmAdapter alarms() { return alarms; }
    public ScheduleNotificationPort notifications() { return notifications; }
    public void setLongExecutionStarter(LongExecutionStarter starter) { longExecution = starter; }
    public void addObserver(Runnable observer) { observers.add(observer); }
    public void removeObserver(Runnable observer) { observers.remove(observer); }
    public boolean userReady() {
        return android.os.Process.myUid() / 100_000 == 0 && android.os.Build.VERSION.SDK_INT >= 31
                && context.getSystemService(UserManager.class).isUserForeground()
                && context.getSystemService(UserManager.class).isUserUnlocked();
    }

    private ScheduleStore requireStore(long deadline) throws TimeoutException {
        ScheduleStore current = store;
        if (current == null) {
            PersistenceRuntimeGraph persistence = PersistenceRuntimeGraph.get(context);
            var admission = persistence.await(deadline - SystemClock.elapsedRealtime());
            if (!admission.available()) throw new ScheduleFailure(MatrixErrorCode.PERSISTENCE_UNAVAILABLE, "加密计划存储不可用");
            current = new ScheduleStore(admission.database(), admission.memory()::currentEpoch, persistence::resetPending,
                    com.matrix.agent.schedule.execution.ScheduleActionPolicy::validate, () -> reminderBlock,
                    this::weatherAdmissionBlock);
            store = current;
        }
        current.checkAvailable();
        return current;
    }

    /** Binder callers capture identity before submitting. A timed-out mutation may have committed; replay its operation ID. */
    public <T> T call(Function<ScheduleStore, T> command) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        long submittedGeneration = commandFence.capture();
        Future<T> future;
        try {
            future = lane.submit(() -> {
                reminderBlock = notifications.blockedReason(ScheduleNotificationPort.REMINDERS);
                ScheduleStore current = requireStore(deadline);
                // Checking inside the same transaction as the command prevents clear/commit races.
                // Reset's durable marker closes in-progress admission; this generation also rejects
                // old queued commands after that marker has already been removed.
                return commandFence.execute(current, submittedGeneration, command);
            });
        } catch (RejectedExecutionException overloaded) {
            throw new ScheduleFailure(MatrixErrorCode.OVERLOADED, "计划服务繁忙");
        }
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { throw new ScheduleFailure(MatrixErrorCode.TIMED_OUT, "操作回执超时；请使用原操作编号查询或重试"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ScheduleFailure(MatrixErrorCode.SERVICE_NOT_READY, "操作已中断"); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            throw new ScheduleFailure(MatrixErrorCode.PERSISTENCE_UNAVAILABLE, "计划存储未就绪");
        }
    }

    public void calendarChanged() {
        try { lane.execute(() -> {
            try { new CalendarBindingStore(requireStore(SystemClock.elapsedRealtime() + 10_000)).invalidateAll(clock.sample()); changed(); }
            catch (Exception unavailable) { ScheduleRepairWorker.enqueue(context); }
        }); } catch (RejectedExecutionException overloaded) { ScheduleRepairWorker.enqueue(context); }
    }
    public void changed() {
        var sample = clock.sample();
        wake("COMMAND", -1, sample.wall().toEpochMilli(), sample.elapsedMillis(), success -> { });
    }
    private String weatherAdmissionBlock(com.matrix.agent.api.schedule.ScheduleAction action) {
        if (action.kind != WORKFLOW || !com.matrix.agent.schedule.workflow.WeatherWorkflow.ID.equals(action.templateId)) return "";
        var parameters = ScheduleCodec.arguments(action.parametersJson);
        if (!action.allowNetwork || !Boolean.TRUE.equals(parameters.get("allowWeatherNetwork"))) return "WEATHER_NETWORK_NOT_AUTHORIZED";
        if (weatherConfig.load() == null) return "WEATHER_NOT_CONFIGURED";
        if (!"CURRENT_AT_TRIGGER".equals(parameters.get("mode"))) return "";
        if (!Boolean.TRUE.equals(parameters.get("allowLocation"))) return "LOCATION_NOT_AUTHORIZED";
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return "LOCATION_PERMISSION_DENIED";
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return "BACKGROUND_LOCATION_DENIED";
        // City-grade cold/background fixes require a working low-power provider. On the
        // reference LineageOS image fused delegates only to GPS, which timed out in T00.
        var location = context.getSystemService(android.location.LocationManager.class);
        if (location == null || !location.getAllProviders().contains(android.location.LocationManager.NETWORK_PROVIDER)
                || !location.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
            return "LOCATION_PROVIDER_UNCERTIFIED";
        return "";
    }

    @Override public void wake(String source, long expectedEpoch, long receivedAt, long receivedElapsed, java.util.function.Consumer<Boolean> completion) {
        long deadline = receivedElapsed + 4_800;
        AtomicBoolean finished = new AtomicBoolean(), succeeded = new AtomicBoolean();
        Runnable finish = () -> { if (finished.compareAndSet(false, true)) completion.accept(succeeded.get()); };
        ScheduledFuture<?> timer = watchdog.schedule(finish, Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        try {
            lane.execute(() -> {
                try {
                    if (SystemClock.elapsedRealtime() >= deadline) return;
                    reminderBlock = notifications.blockedReason(ScheduleNotificationPort.REMINDERS);
                    ScheduleStore current = requireStore(deadline);
                    if (expectedEpoch >= 0 && expectedEpoch != current.currentEpoch()) return;
                    ScheduleAdmissionStore admission = new ScheduleAdmissionStore(current);
                    if (!"COMMAND".equals(source)) admission.invalidateArm();
                    boolean ready = userReady();
                    if (Intent.ACTION_BOOT_COMPLETED.equals(source) || Intent.ACTION_USER_UNLOCKED.equals(source)
                            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(source) || "REPAIR".equals(source)) {
                        new CalendarBindingStore(current).invalidateAll(clock.sample());
                    }
                    boolean clocksChanged = Intent.ACTION_TIME_CHANGED.equals(source) || Intent.ACTION_TIMEZONE_CHANGED.equals(source);
                    arm(admission, admission.reconcile(clock.sample(), clocksChanged, ready));
                    // System changes only reconcile registration. The due alarm performs business admission.
                    if (!ready || clocksChanged || "COMMAND".equals(source) || source != null && source.startsWith("android.app.action.")) { succeeded.set(true); return; }
                    for (ScheduleOutboxEntity intent : current.dao().pendingOutbox(current.currentEpoch())) {
                        if (SystemClock.elapsedRealtime() >= deadline || !userReady()) break;
                        if (intent.nextAttemptAt > clock.sample().wall().toEpochMilli()) continue;
                        if (ScheduleAdmissionStore.ADMIT.equals(intent.kind)) {
                            if (ScheduleAlarmAdapter.ACTION_DUE.equals(source)) admission.recordReceipt(intent.effectId, receivedAt, receivedElapsed);
                            admission.attempted(intent.effectId, clock.sample(), "ADMISSION_STARTED");
                            var plan = current.dao().definition(intent.scheduleId);
                            if (plan != null && ScheduleCodec.rule(plan.timeRuleJson) instanceof TimeRule.CalendarOffset) {
                                if (longExecution != null) longExecution.start("calendar:" + plan.scheduleId);
                            } else admission.admitOne(intent.effectId, clock.sample(),
                                    ScheduleAlarmAdapter.ACTION_DUE.equals(source) ? receivedAt : 0, receivedElapsed);
                        }
                    }
                    // The second page includes dispatches created by this admission batch.
                    for (ScheduleOutboxEntity intent : current.dao().pendingOutbox(current.currentEpoch())) {
                        if (SystemClock.elapsedRealtime() >= deadline || !userReady()) break;
                        if (intent.nextAttemptAt > clock.sample().wall().toEpochMilli()) continue;
                        if ("CALENDAR_REFRESH".equals(intent.kind)) {
                            admission.attempted(intent.effectId, clock.sample(), "CALENDAR_RECONCILIATION");
                            if (longExecution != null) longExecution.start("calendar:" + intent.scheduleId);
                            continue;
                        }
                        if ("DELIVER_RESULT".equals(intent.kind)) {
                            ScheduleRunEntity retained = current.dao().run(intent.runId);
                            if (retained == null || retained.deliveryStatus != DELIVERY_PENDING) current.dao().deleteOutbox(intent.effectId);
                            else if (clock.sample().wall().toEpochMilli() > intent.cutoffAt) {
                                admission.finish(retained.runId, retained.state, DELIVERY_FAILED, retained.result, "DELIVERY_EXPIRED", clock.sample(), false);
                            } else if (authorized(retained)) {
                                admission.attempted(intent.effectId, clock.sample(), "RESULT_DELIVERY_STARTED");
                                if (ScheduleCodec.spec(retained.specJson).action.speakResult && longExecution != null) longExecution.start(retained.runId);
                                else {
                                    String blocked = notifications.postResult(retained, retained.result.isEmpty() ? retained.reason : retained.result);
                                    admission.recordDelivery(retained.runId, "notification", blocked.isEmpty() ? "DELIVERED" : blocked,
                                            notifications.policySnapshot(ScheduleNotificationPort.resultChannel(retained)), clock.sample());
                                    admission.finish(retained.runId, !blocked.isEmpty() && retained.state == SUCCEEDED ? PARTIAL : retained.state, blocked.isEmpty() ? DELIVERED : DELIVERY_BLOCKED,
                                            retained.result, blocked.isEmpty() ? retained.reason : blocked, clock.sample(), blocked.isEmpty());
                                }
                            } else admission.finish(retained.runId, retained.state, DELIVERY_BLOCKED, retained.result, "AUTHORIZATION_REVOKED", clock.sample(), false);
                            continue;
                        }
                        if ("EXECUTE".equals(intent.kind)) {
                            ScheduleRunEntity retained = current.dao().run(intent.runId);
                            if (retained == null || terminalRun(retained.state)) current.dao().deleteOutbox(intent.effectId);
                            else {
                                admission.attempted(intent.effectId, clock.sample(), "EXECUTION_RECOVERY");
                                if (longExecution != null) longExecution.start(retained.runId);
                            }
                            continue;
                        }
                        if (!ScheduleAdmissionStore.DISPATCH.equals(intent.kind)) continue;
                        ScheduleRunEntity run = admission.claim(intent.effectId, clock.sample());
                        if (run == null) continue;
                        admission.attempted(intent.effectId, clock.sample(), "DISPATCH_STARTED");
                        if (!authorized(run)) {
                            admission.finish(run.runId, FAILED, DELIVERY_BLOCKED, "", "AUTHORIZATION_REVOKED", clock.sample(), false);
                            continue;
                        }
                        if (ScheduleCodec.spec(run.specJson).timing.kind == CALENDAR_OFFSET) {
                            if (longExecution != null) longExecution.start(run.runId);
                            continue;
                        }
                        if (ScheduleCodec.spec(run.specJson).action.kind == NOTIFICATION) {
                            if (SystemClock.elapsedRealtime() >= deadline || !userReady() || current.currentEpoch() != run.dataEpoch) break;
                            String blocked = notifications.post(run);
                            admission.recordDelivery(run.runId, "notification", blocked.isEmpty() ? "DELIVERED" : blocked,
                                    notifications.policySnapshot(ScheduleNotificationPort.REMINDERS), clock.sample());
                            admission.finish(run.runId, blocked.isEmpty() ? SUCCEEDED : FAILED,
                                    blocked.isEmpty() ? DELIVERED : DELIVERY_BLOCKED, "", blocked, clock.sample(), blocked.isEmpty());
                        } else {
                            var action = ScheduleCodec.spec(run.specJson).action;
                            if (action.kind == WORKFLOW
                                    && com.matrix.agent.schedule.workflow.WeatherWorkflow.ID.equals(action.templateId)) {
                                String blocked = notifications.postWeatherPending(run);
                                admission.recordDelivery(run.runId, "notification", blocked.isEmpty() ? "DELIVERED" : blocked,
                                        notifications.policySnapshot(ScheduleNotificationPort.REMINDERS), clock.sample());
                            }
                            LongExecutionStarter starter = longExecution;
                            if (starter != null) starter.start(run.runId);
                            else throw new IllegalStateException("scheduled execution starter unavailable");
                        }
                    }
                    arm(admission, admission.reconcile(clock.sample(), false, userReady()));
                    if (SystemClock.elapsedRealtime() < deadline - 300) ScheduleRetention.prune(current, clock.sample().wall().toEpochMilli());
                    succeeded.set(true);
                } catch (Exception failure) {
                    // No contents, model configuration or user goal is logged.
                    Log.w("MatrixSchedule", "cold admission failed: " + failure.getClass().getSimpleName());
                    ScheduleRepairWorker.enqueue(context);
                } finally {
                    timer.cancel(false); finish.run();
                    for (Runnable observer : observers) observer.run();
                }
            });
        } catch (RejectedExecutionException overloaded) { timer.cancel(false); finish.run(); ScheduleRepairWorker.enqueue(context); }
    }

    private void arm(ScheduleAdmissionStore admission, com.matrix.agent.data.schedule.ScheduleArmEntity arm) {
        String failure = arm.appliedGeneration == arm.armGeneration && arm.state == ARMED ? "" : alarms.apply(arm);
        admission.acknowledgeArm(arm.dataEpoch, arm.armGeneration, failure);
    }

    public boolean authorized(ScheduleIdentity owner) {
        if (!userReady()) return false;
        try {
            return owner.androidUserId() == 0 && context.getPackageManager().getPackageUid(owner.packageName(), 0) == owner.uid()
                    && owner.signatureDigest().equals(ScheduleCallerIdentity.signature(context, owner.packageName()))
                    && (owner.uid() == android.os.Process.SYSTEM_UID || context.getPackageManager().checkPermission(
                            "com.matrix.agent.permission.ACCESS_AGENT", owner.packageName()) == PackageManager.PERMISSION_GRANTED);
        } catch (Exception revoked) { return false; }
    }
    public boolean authorized(ScheduleRunEntity run) {
        if (!userReady()) return false;
        try {
            org.json.JSONObject owner = new org.json.JSONObject(run.authorizationJson);
            String pkg = owner.getString("package"); int uid = owner.getInt("uid");
            if (owner.getInt("user") != 0 || context.getPackageManager().getPackageUid(pkg, 0) != uid) return false;
            return owner.getString("signature").equals(ScheduleCallerIdentity.signature(context, pkg))
                    && (uid == android.os.Process.SYSTEM_UID || context.getPackageManager().checkPermission(
                            "com.matrix.agent.permission.ACCESS_AGENT", pkg) == PackageManager.PERMISSION_GRANTED);
        } catch (Exception revoked) { return false; }
    }

    /** Called from the existing clear-all transaction lifecycle; old callbacks remain epoch-fenced. */
    public AutoCloseable holdExecutionCpu(long millis) {
        return holdExecutionCpu(millis, com.matrix.agent.identity.ExecutionProfile.INTERACTIVE);
    }
    public AutoCloseable holdExecutionCpu(long millis, com.matrix.agent.identity.ExecutionProfile profile) {
        var lease = context.getSystemService(android.os.PowerManager.class).newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK, "MatrixAgent:scheduled-execution");
        lease.setReferenceCounted(false); lease.acquire(Math.min(profile.maxActiveMillis(), Math.max(1, millis)));
        return () -> { if (lease.isHeld()) lease.release(); };
    }
    public void close() { lane.shutdownNow(); watchdog.shutdownNow(); observers.clear(); }
    public void reset() { commandFence.invalidate(); alarms.cancel(); notifications.clear(); }
}
