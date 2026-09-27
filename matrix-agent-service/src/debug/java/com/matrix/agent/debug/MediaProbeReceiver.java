package com.matrix.agent.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.host.di.RuntimeProfileResolver;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.platform.media.AndroidAppLaunchPort;
import com.matrix.agent.platform.media.AndroidMediaSessionPort;
import com.matrix.agent.platform.media.AndroidPackageProbe;
import com.matrix.agent.platform.media.AndroidQQMusicUiPort;
import com.matrix.agent.platform.media.MediaCapabilityProvider;
import com.matrix.agent.platform.media.MediaSelectionUtterance;
import com.matrix.agent.task.capability.CapabilityDefinition;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.policy.PolicyDecision;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.skill.QQMusicWorkflowGateway;
import com.matrix.agent.task.skill.MediaSwitchGuard;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.vehicle.VehicleState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug-only, privileged device probe. Runs the production policy/executor/provider sequence
 * without installing an instrumentation APK or exposing raw media metadata in logcat.
 */
public final class MediaProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "MatrixMediaProbe";
    private static volatile MediaCapabilityProvider provider;

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                run(context.getApplicationContext(), intent);
            } catch (RuntimeException error) {
                Log.e(TAG, "probe failed cause=" + error.getClass().getSimpleName());
            } finally {
                pending.finish();
            }
        }, "matrix-media-probe").start();
    }

    private static void run(Context context, Intent intent) {
        if (intent.hasExtra("switch_guard_text")) {
            String command = intent.getStringExtra("switch_guard_text");
            if (command == null || command.isBlank()) return;
            AgentRequest request = AgentRequest.builder(command, Actor.DRIVER).build();
            PolicyDecision decision = new MediaSwitchGuard(request).before(
                    new ToolCall(MediaCapabilities.QQ_PLAY, Map.of()));
            Log.i(TAG, "switchGuard decision=" + (decision == null ? "NOT_ACTIVE"
                    : decision.getRejectionType()) + " capability=media.qqmusic.play");
            return;
        }
        if (intent.hasExtra("engine_reject_text")) {
            runEngineReject(context, intent.getStringExtra("engine_reject_text"));
            return;
        }
        if (intent.hasExtra("engine_search_text")) {
            runEngineSearch(context, intent.getStringExtra("engine_search_text"));
            return;
        }
        if (intent.hasExtra("workflow_text")) {
            runSearchWorkflow(context, intent.getStringExtra("workflow_text"));
            return;
        }
        String capability = intent.getStringExtra("capability");
        CapabilityRegistry registry = CapabilityRegistry.createRuntimeRegistry();
        CapabilityDefinition definition = registry.find(capability);
        if (definition == null || !capability.startsWith("media.")) {
            Log.w(TAG, "invalid capability");
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (intent.hasExtra("bvid")) args.put("bvid", intent.getStringExtra("bvid"));
        if (intent.hasExtra("page")) args.put("page", intent.getIntExtra("page", 0));
        if (intent.hasExtra("position_ms")) {
            args.put("position_ms", intent.getLongExtra("position_ms", -1L));
        }
        if (intent.hasExtra("query")) args.put("query", intent.getStringExtra("query"));
        if (intent.hasExtra("index")) args.put("index", intent.getIntExtra("index", -1));
        ToolCall call = new ToolCall(capability, args);
        String requestText = intent.getStringExtra("request_text");
        AgentRequest request = AgentRequest.builder(
                        requestText == null ? "真机媒体调试操作" : requestText, Actor.DRIVER)
                .sessionId("media-probe-session")
                .runtimeProfile(new RuntimeProfileResolver(context).snapshot())
                .vehicleState(VehicleState.unavailable())
                .timeoutMillis(15_000L)
                .build();
        PolicyDecision decision = new PolicyEngine(registry).evaluate(request, call);
        if (!decision.isAllowed()) {
            Log.i(TAG, "cap=" + capability + " policy=DENIED type="
                    + decision.getRejectionType());
            return;
        }
        MediaCapabilityProvider current = provider(context);
        ToolExecutor executor = new ToolExecutor(1);
        try {
            ToolResult result = executor.execute(current, definition, request, call);
            Map<String, Object> observed = result.getObservedState();
            Log.i(TAG, "cap=" + capability + " status=" + result.getStatus()
                    + " verified=" + result.isVerified()
                    + " error=" + observed.get("media.error_code")
                    + " dispatch=" + observed.get("media.dispatch_state")
                    + " playback=" + observed.get("media.playback_state")
                    + " candidateCount=" + (observed.get("media.candidates") instanceof java.util.List
                            ? ((java.util.List<?>) observed.get("media.candidates")).size() : 0)
                    + " confirmableIndex=" + observed.get("media.confirmable_index")
                    + " actions=" + observed.get("media.supported_actions"));
        } finally {
            executor.shutdown();
        }
    }

    private static synchronized MediaCapabilityProvider provider(Context context) {
        if (provider == null) {
            var handoff = ((MatrixAgentApplication) context).getContainer().getHandoffCoordinator();
            AndroidAppLaunchPort launcher = new AndroidAppLaunchPort(context, handoff);
            provider = new MediaCapabilityProvider(new AndroidPackageProbe(context),
                    new AndroidMediaSessionPort(context), launcher,
                    new AndroidQQMusicUiPort(context, launcher),
                    new com.matrix.agent.platform.media.AndroidBilibiliUiPort(context,
                            launcher), handoff);
        }
        return provider;
    }

    /** Runs the real repository/AgentEngine path for one search request; never submits playback. */
    private static void runEngineSearch(Context context, String text) {
        if (text == null || !text.matches("播放[\\p{IsHan}A-Za-z0-9]{2,24}的.{1,48}")) {
            Log.w(TAG, "engine search probe rejected input shape");
            return;
        }
        long started = android.os.SystemClock.elapsedRealtime();
        MatrixAgentApplication application = (MatrixAgentApplication) context;
        AgentOutcome outcome = application.getContainer().getAgentRuntimeRepository()
                .executeForSession(text, Actor.DRIVER, "probe-qq-search", new CancellationToken());
        Log.i(TAG, "engineSearch state=" + outcome.getFinalState()
                + " stop=" + outcome.getStopReason()
                + " toolCalls=" + outcome.getInternalResults().size()
                + " answerReady=" + (outcome.getFinalAssistantText() != null)
                + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
    }

    private static void runEngineReject(Context context, String text) {
        if (!MediaSelectionUtterance.isNegative(text)) {
            Log.w(TAG, "engine reject probe accepted only negative replies");
            return;
        }
        long started = android.os.SystemClock.elapsedRealtime();
        MatrixAgentApplication application = (MatrixAgentApplication) context;
        AgentOutcome outcome = application.getContainer().getAgentRuntimeRepository()
                .executeForSession(text, Actor.DRIVER, "probe-qq-search", new CancellationToken());
        Log.i(TAG, "engineReject state=" + outcome.getFinalState()
                + " stop=" + outcome.getStopReason()
                + " toolCalls=" + outcome.getInternalResults().size()
                + " answerReady=" + (outcome.getFinalAssistantText() != null)
                + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
    }

    /** Safe device integration probe: executes search and answer, never selection or playback. */
    private static void runSearchWorkflow(Context context, String text) {
        long started = android.os.SystemClock.elapsedRealtime();
        MediaCapabilityProvider current = provider(context);
        QQMusicWorkflowGateway gateway = new QQMusicWorkflowGateway(
                request -> { throw new AssertionError("remote model unexpectedly called"); },
                current);
        AgentRequest request = AgentRequest.builder(text, Actor.DRIVER)
                .sessionId("media-probe-workflow")
                .runtimeProfile(new RuntimeProfileResolver(context).snapshot())
                .vehicleState(VehicleState.unavailable())
                .timeoutMillis(15_000L).build();
        AgentMessage user = AgentMessage.user(text);
        ModelTurn first = gateway.decide(new ModelTurnRequest(request,
                java.util.List.of(user), java.util.List.of(), "media workflow probe",
                new SessionContext()));
        if (!first.hasToolCalls() || !MediaCapabilities.QQ_SEARCH.equals(
                first.getToolCalls().get(0).getCapabilityName())) {
            Log.w(TAG, "workflow probe did not select search");
            return;
        }
        ToolCall call = first.getToolCalls().get(0);
        CapabilityRegistry registry = CapabilityRegistry.createRuntimeRegistry();
        PolicyDecision decision = new PolicyEngine(registry).evaluate(request, call);
        if (!decision.isAllowed()) {
            Log.w(TAG, "workflow probe search denied");
            return;
        }
        ToolExecutor executor = new ToolExecutor(1);
        try {
            ToolResult result = executor.execute(current, registry.find(MediaCapabilities.QQ_SEARCH),
                    request, call);
            ModelTurn answer = gateway.decide(new ModelTurnRequest(request,
                    java.util.List.of(user, first.getAssistantMessage(),
                            ToolObservation.of(call, result).toToolMessage()),
                    java.util.List.of(), "media workflow probe", new SessionContext()));
            Log.i(TAG, "workflowSearch status=" + result.getStatus()
                    + " confirmableIndex=" + result.getObservedState().get("media.confirmable_index")
                    + " answerReady=" + !answer.hasToolCalls()
                    + " answerChars=" + answer.getAssistantMessage().getContent().length()
                    + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started)
                    + " remoteModelCalls=0");
        } finally {
            executor.shutdown();
        }
    }
}
