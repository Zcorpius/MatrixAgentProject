package com.matrix.agent.launcher.data;

import com.matrix.agent.api.schedule.*;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.client.ScheduleManager;
import java.util.List;
import java.util.function.Consumer;

/** Schedule data source; neither ViewModel nor UI holds a Binder or SDK manager. */
public final class ScheduleRepository {
    private final LauncherHostGateway gateway;
    public ScheduleRepository(LauncherHostGateway gateway) { this.gateway = gateway; }
    public record Snapshot(ScheduleReadiness readiness, SchedulePage plans, ScheduleRunPage runs,
            List<ScheduleTemplateInfo> templates, int featureFlags) { }
    public record RunDetail(ScheduleRunInfo run, ScheduleInfo plan, List<ScheduleStepInfo> steps) { }
    public record PlanDetail(ScheduleInfo plan, ScheduleRunPage runs) { }
    public boolean connected() { return gateway.isConnected(); }
    public void load(Snapshot previous, Consumer<LauncherHostGateway.Result<Snapshot>> receiver) {
        gateway.execute(agent -> {
            ScheduleManager manager = agent.getScheduleManager();
            if (manager == null) throw new IllegalStateException("Host 尚未提供定时任务功能");
            var plans = plans(manager, previous == null ? 100 : previous.plans.items.size());
            var runs = runs(manager, "", previous == null ? 100 : previous.runs.items.size());
            if (plans.code != MatrixErrorCode.SUCCESS || runs.code != MatrixErrorCode.SUCCESS) throw new IllegalStateException("计划读取失败（" + plans.code + "/" + runs.code + "）");
            return new Snapshot(manager.getReadiness(), plans, runs, manager.listTemplates(), agent.getFeatureFlags());
        }, receiver);
    }
    public void more(Snapshot previous, boolean plans, Consumer<LauncherHostGateway.Result<Snapshot>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getScheduleManager();
            if (plans) {
                var page = manager.list(previous.plans.nextCursor, 100); if (page.code != 0) throw new IllegalStateException("计划分页读取失败");
                java.util.LinkedHashMap<String, ScheduleInfo> values = new java.util.LinkedHashMap<>();
                for (var item : previous.plans.items) values.put(item.scheduleId, item);
                for (var item : page.items) values.put(item.scheduleId, item);
                return new Snapshot(previous.readiness, new SchedulePage(0, List.copyOf(values.values()), page.nextCursor, page.sequence), previous.runs, previous.templates, previous.featureFlags);
            }
            var page = manager.listRuns("", previous.runs.nextCursor, 100); if (page.code != 0) throw new IllegalStateException("运行分页读取失败");
            java.util.LinkedHashMap<String, ScheduleRunInfo> values = new java.util.LinkedHashMap<>();
            for (var item : previous.runs.items) values.put(item.runId, item);
            for (var item : page.items) values.put(item.runId, item);
            return new Snapshot(previous.readiness, previous.plans, new ScheduleRunPage(0, List.copyOf(values.values()), page.nextCursor, page.sequence), previous.templates, previous.featureFlags);
        }, receiver);
    }
    private static SchedulePage plans(ScheduleManager manager, int count) {
        var window = SchedulePageWindow.read(Math.max(100, count), cursor -> {
            var page = manager.list(cursor, 100);
            if (page.code != 0) throw new IllegalStateException("计划读取失败（" + page.code + "）");
            return new SchedulePageWindow.Page<>(page.items, page.nextCursor, page.sequence);
        }, plan -> plan.scheduleId);
        return new SchedulePage(0, window.items(), window.nextCursor(), window.sequence());
    }
    private static ScheduleRunPage runs(ScheduleManager manager, String plan, int count) {
        var window = SchedulePageWindow.read(Math.max(100, count), cursor -> {
            var page = manager.listRuns(plan, cursor, 100);
            if (page.code != 0) throw new IllegalStateException("运行读取失败（" + page.code + "）");
            return new SchedulePageWindow.Page<>(page.items, page.nextCursor, page.sequence);
        }, run -> run.runId);
        return new ScheduleRunPage(0, window.items(), window.nextCursor(), window.sequence());
    }
    public void planDetail(String id, int loadedCount, Consumer<LauncherHostGateway.Result<PlanDetail>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getScheduleManager();
            return new PlanDetail(manager.get(id), runs(manager, id, loadedCount));
        }, receiver);
    }
    public void morePlanRuns(PlanDetail previous, Consumer<LauncherHostGateway.Result<PlanDetail>> receiver) {
        gateway.execute(agent -> {
            var page = agent.getScheduleManager().listRuns(previous.plan.scheduleId, previous.runs.nextCursor, 100);
            if (page.code != 0) throw new IllegalStateException("计划运行历史读取失败（" + page.code + "）");
            var values = new java.util.LinkedHashMap<String, ScheduleRunInfo>();
            for (var run : previous.runs.items) values.put(run.runId, run);
            for (var run : page.items) values.put(run.runId, run);
            return new PlanDetail(previous.plan, new ScheduleRunPage(0, List.copyOf(values.values()), page.nextCursor, page.sequence));
        }, receiver);
    }
    public void detail(String id, Consumer<LauncherHostGateway.Result<RunDetail>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getScheduleManager(); var run = manager.getRun(id);
            return new RunDetail(run, manager.get(run.scheduleId), manager.getSteps(id));
        }, receiver);
    }
    public void plan(String id, Consumer<LauncherHostGateway.Result<ScheduleInfo>> receiver) {
        gateway.execute(agent -> agent.getScheduleManager().get(id), receiver);
    }
    public void preview(ScheduleSpec spec, Consumer<LauncherHostGateway.Result<SchedulePreview>> receiver) {
        gateway.execute(agent -> agent.getScheduleManager().preview(spec), receiver);
    }
    public void save(ScheduleInfo existing, ScheduleSpec spec, String operationId,
            Consumer<LauncherHostGateway.Result<ScheduleMutation>> receiver) {
        gateway.execute(agent -> existing == null ? agent.getScheduleManager().create(spec, operationId)
                : agent.getScheduleManager().update(existing.scheduleId, existing.revision, spec, operationId), receiver);
    }
    public void control(ScheduleInfo plan, String run, int command, String operationId,
            Consumer<LauncherHostGateway.Result<ScheduleMutation>> receiver) {
        gateway.execute(agent -> agent.getScheduleManager().control(plan.scheduleId, run, plan.revision, command, operationId), receiver);
    }
    public void calendar(String capability, String params, String operation, Consumer<LauncherHostGateway.Result<ScheduleCalendarResult>> reply) {
        gateway.execute(agent -> agent.getScheduleManager().calendar(capability, params, operation), reply);
    }
    public void bindCalendar(long event, long original, String reminderOwner, String operation, Consumer<LauncherHostGateway.Result<ScheduleCalendarResult>> reply) {
        gateway.execute(agent -> agent.getScheduleManager().bindCalendar(event, original, reminderOwner, operation), reply);
    }
    public void subscribe(long after, Runnable changed, Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
        gateway.execute(agent -> agent.getScheduleManager().subscribe(after, event -> gateway.dispatchToMain(changed)), receiver);
    }
}
