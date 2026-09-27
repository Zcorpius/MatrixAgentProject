package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.schedule.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Scheduling API. Invoke synchronous methods off the UI thread; listeners use the facade event handler. */
public final class ScheduleManager extends MatrixManagerBase {
    private volatile IScheduleService service;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    ScheduleManager(MatrixAgent agent, IBinder binder) { super(agent, binder); service = IScheduleService.Stub.asInterface(binder); }

    /** Read errors are explicit, never an empty successful plan list. */
    public static final class Unavailable extends RuntimeException {
        public final int code;
        Unavailable(int code, String message) { super(message); this.code = code; }
    }
    @FunctionalInterface private interface RemoteCall<T> { T run(IScheduleService service) throws RemoteException; }
    private <T> T call(RemoteCall<T> action) {
        IScheduleService current = service;
        if (current == null) throw new Unavailable(MatrixErrorCode.SERVICE_NOT_READY, "计划服务未连接");
        try { return action.run(current); }
        catch (IllegalStateException failure) { throw new Unavailable(MatrixErrorCode.SERVICE_NOT_READY, failure.getMessage()); }
        catch (RemoteException disconnected) {
            handleRemoteException(disconnected);
            throw new Unavailable(MatrixErrorCode.SERVICE_NOT_READY, "计划服务连接中断");
        }
    }
    public ScheduleReadiness getReadiness() { return call(IScheduleService::getReadiness); }
    public SchedulePreview preview(ScheduleSpec spec) { return call(s -> s.preview(spec)); }
    public ScheduleMutation create(ScheduleSpec spec, String operationId) { return call(s -> s.create(spec, operationId)); }
    public ScheduleMutation update(String id, long revision, ScheduleSpec spec, String operationId) { return call(s -> s.update(id, revision, spec, operationId)); }
    public ScheduleMutation control(String id, String run, long revision, int operation, String operationId) { return call(s -> s.control(id, run, revision, operation, operationId)); }
    public ScheduleInfo get(String id) { return call(s -> s.get(id)); }
    public SchedulePage list(String cursor, int limit) { return call(s -> s.list(cursor, limit)); }
    public ScheduleRunInfo getRun(String id) { return call(s -> s.getRun(id)); }
    public ScheduleRunPage listRuns(String schedule, String cursor, int limit) { return call(s -> s.listRuns(schedule, cursor, limit)); }
    public List<ScheduleStepInfo> getSteps(String run) { return call(s -> s.getSteps(run)); }
    public List<ScheduleTemplateInfo> listTemplates() { return call(IScheduleService::listTemplates); }

    public ScheduleCalendarResult calendar(String capability, String parametersJson, String operationId) { return call(s -> s.calendar(capability, parametersJson, operationId)); }
    public ScheduleCalendarResult bindCalendar(long eventId, long originalStartMillis, String reminderOwner, String operationId) { return call(s -> s.bindCalendar(eventId, originalStartMillis, reminderOwner, operationId)); }
    public ScheduleCalendarResult calendarBindings() { return call(IScheduleService::calendarBindings); }

    @FunctionalInterface public interface Listener { void onChanged(ScheduleEvent event); }
    public AutoCloseable subscribe(long afterSequence, Listener listener) {
        if (afterSequence < 0 || listener == null) throw new IllegalArgumentException("invalid subscription");
        Subscription subscription = new Subscription(afterSequence, listener);
        subscriptions.add(subscription);
        try { subscription.register(); }
        catch (RuntimeException failed) { subscriptions.remove(subscription); throw failed; }
        return subscription;
    }
    private final class Subscription implements AutoCloseable {
        final AtomicLong sequence;
        final AtomicBoolean closed = new AtomicBoolean();
        final Listener listener;
        final IScheduleCallback callback = new IScheduleCallback.Stub() {
            @Override public void onChanged(ScheduleEvent event) {
                if (event == null || closed.get()) return;
                eventHandler().post(() -> {
                    if (closed.get() || event.sequence <= sequence.get()) return;
                    sequence.set(event.sequence); listener.onChanged(event);
                });
            }
        };
        Subscription(long sequence, Listener listener) { this.sequence = new AtomicLong(sequence); this.listener = listener; }
        void register() { if (!closed.get()) call(s -> { s.subscribe(sequence.get(), callback); return null; }); }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            subscriptions.remove(this); IScheduleService current = service;
            if (current != null) try { current.unsubscribe(callback); } catch (RemoteException failure) { handleRemoteException(failure); }
        }
    }
    @Override protected void onMatrixServiceDisconnected() { service = null; serviceBinder = null; }
    @Override protected void onMatrixServiceConnected(IBinder binder) {
        serviceBinder = binder; service = IScheduleService.Stub.asInterface(binder);
        for (Subscription subscription : subscriptions) try { subscription.register(); } catch (Unavailable ignored) { break; }
    }
}
