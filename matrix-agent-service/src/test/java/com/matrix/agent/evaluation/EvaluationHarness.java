package com.matrix.agent.evaluation;

import static com.matrix.agent.evaluation.EvaluationCase.*;

import com.matrix.agent.contract.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.task.*;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.prompt.DefaultPromptBuilder;
import com.matrix.agent.task.prompt.PromptContextAssembler;
import com.matrix.agent.task.skill.*;
import com.matrix.agent.task.tool.ToolExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** One world/session per case, one request per user turn; no real memory or audit persistence. */
final class EvaluationHarness implements AutoCloseable {
    record Trace(AgentOutcome outcome, List<Action> modelProposals, List<Action> engineProposals,
            List<Action> effects, List<Action> observations, List<String> systemPromptHashes,
            int modelCalls, String harnessError, List<Action> policyRejections) {}

    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final ExecutorService local = Executors.newSingleThreadExecutor();
    private final ExecutorService tools = Executors.newFixedThreadPool(2);
    private final Path assets;
    private final AgentBudget budget = new AgentBudget();

    EvaluationHarness(Path assets) { this.assets = assets; }

    List<Trace> run(EvaluationCase scenario, ModelGateway realModel) {
        return run(scenario, realModel, null);
    }
    List<Trace> run(EvaluationCase scenario, ModelGateway realModel,
            com.matrix.agent.failure.FailureLessonRecaller lessons) {
        FixtureWorld world = new FixtureWorld(scenario.fixtures());
        CapabilityRegistry registry = CapabilityRegistry.createRuntimeRegistry();
        SkillSelector selector = new SkillSelector(EvaluationSkills.load(assets, registry),
                world::availability, world.media, world.media);
        var configuration = new AgentEngineConfiguration.Builder().promptContextAssembler(
                new PromptContextAssembler(null, new DefaultPromptBuilder(), selector,
                        budget.getMaxMessageChars(), lessons)).build();
        List<AgentMessage> history = new ArrayList<>();
        List<Trace> traces = new ArrayList<>();
        SessionManager sessions = new SessionManager();
        SessionLockManager locks = new SessionLockManager();
        for (Turn turn : scenario.turns()) {
            world.beginTurn(scenario.id(), turn.expireCandidates());
            ScriptedGateway script = new ScriptedGateway(turn.script());
            RecordingGateway model = new RecordingGateway(realModel == null ? script : realModel);
            RecordingGateway workflow = new RecordingGateway(new QQMusicWorkflowGateway(
                    new BilibiliTitleGateway(model, world.media), world.media));
            AgentEngine engine = new AgentEngine(workflow, new ModelCallExecutor(2, network, local),
                    new PolicyEngine(registry), registry, world, sessions, new DefaultContextUpdater(),
                    locks, new ToolExecutor(2, tools), budget, null, configuration);
            CancellationToken token = new CancellationToken();
            var builder = AgentRequest.builder(turn.text(), Actor.DRIVER)
                    .sessionId(scenario.id()).occupantZone(VehicleZone.DRIVER)
                    .runtimeProfile(RuntimeProfile.PHONE)
                    .explicitIntent(ExplicitIntentConstraints.extractFrom(turn.text()))
                    .timeoutMillis(budget.getTotalDeadlineMillis()).cancellationToken(token)
                    .conversationSeed(new ConversationSeedContext(history));
            if (turn.interactive()) builder.interactiveOrigin(new InteractiveOrigin(
                    10001, 0, "evaluation.synthetic", turn.originText()));
            AgentOutcome outcome;
            try { outcome = engine.execute(builder.build()); }
            finally { token.cancel(); }
            traces.add(new Trace(outcome, model.proposals(), workflow.proposals(), world.effects(),
                    world.observations(), workflow.hashes(), model.calls.get(),
                    realModel == null ? script.error() : "", workflow.rejections(outcome)));
            // Same seed contract as production: only completed user/assistant pairs, never audit/tool text.
            if (outcome.getFinalState() == TaskState.SUCCEEDED && outcome.getFinalAssistantText() != null && !outcome.getFinalAssistantText().isBlank()) {
                history.add(AgentMessage.user(turn.text()));
                history.add(AgentMessage.assistant(outcome.getFinalAssistantText(), List.of()));
            }
        }
        return List.copyOf(traces);
    }

    private static final class ScriptedGateway implements ModelGateway {
        private final List<ScriptStep> steps;
        private final AtomicInteger index = new AtomicInteger();
        ScriptedGateway(List<ScriptStep> steps) { this.steps = steps; }
        @Override public ModelTurn decide(ModelTurnRequest request) {
            int current = index.getAndIncrement();
            if (current >= steps.size()) throw new IllegalStateException("evaluation script exhausted");
            ScriptStep step = steps.get(current);
            return step.calls().isEmpty() ? ModelTurn.directAnswer(step.answer())
                    : ModelTurn.ofToolCalls(step.calls().stream()
                            .map(action -> new ToolCall(action.kind(), action.attributes())).toList(), step.answer());
        }
        String error() { return index.get() == steps.size() ? "" : "SCRIPT_CONSUMPTION_MISMATCH"; }
    }

    /** Preserve cancellation and lane selection; instrumentation must not change execution semantics. */
    private static final class RecordingGateway implements ModelGateway {
        private final ModelGateway delegate;
        private final List<Action> proposed = new ArrayList<>();
        private final List<String> hashes = new ArrayList<>();
        private final java.util.Map<String, ToolCall> rawCalls = new java.util.LinkedHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        RecordingGateway(ModelGateway delegate) { this.delegate = delegate; }
        @Override public ExecutionLane executionLane() { return delegate.executionLane(); }
        @Override public ModelTurn decide(ModelTurnRequest request) { return prepare(request).call(); }
        @Override public CancellableModelCall prepare(ModelTurnRequest request) {
            CancellableModelCall call = delegate.prepare(request);
            return new CancellableModelCall() {
                @Override public ModelTurn call() {
                    calls.incrementAndGet();
                    synchronized (RecordingGateway.this) {
                        hashes.add(Provenance.sha256(request.getSystemPrompt()));
                    }
                    ModelTurn result = call.call();
                    synchronized (RecordingGateway.this) {
                        result.getToolCalls().forEach(tool -> {
                            proposed.add(new Action(tool.getCapabilityName(), tool.getArguments()));
                            rawCalls.put(tool.getStepId(), tool);
                        });
                    }
                    return result;
                }
                @Override public void abort() { call.abort(); }
            };
        }
        synchronized List<Action> proposals() { return List.copyOf(proposed); }
        synchronized List<String> hashes() { return List.copyOf(hashes); }
        synchronized List<Action> rejections(AgentOutcome outcome) {
            List<Action> result = new ArrayList<>();
            for (AgentIteration iteration : outcome.getTrajectory().getIterations()) {
                for (ToolObservation observation : iteration.getObservations()) {
                    ToolCall call = rawCalls.get(observation.getToolCallId());
                    if (call != null && observation.getRejectionReason() != null) {
                        result.add(new Action(call.getCapabilityName(), java.util.Map.of(
                                "arguments", call.getArguments(), "capabilityBlocked", observation.isCapabilityBlocked(),
                                "reasonSha256", Provenance.sha256(observation.getRejectionReason()))));
                    }
                }
            }
            return List.copyOf(result);
        }
    }

    @Override public void close() {
        List<ExecutorService> executors = List.of(network, local, tools);
        executors.forEach(ExecutorService::shutdownNow);
        for (ExecutorService executor : executors) {
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("evaluation worker did not terminate");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("evaluation interrupted", interrupted);
            }
        }
    }
}
