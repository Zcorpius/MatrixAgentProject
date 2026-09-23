package com.matrix.agent.host.rpc;

import android.os.RemoteException;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.api.conversation.CapabilityTraceEntry;
import com.matrix.agent.api.conversation.ConversationDraft;
import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationListQuery;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationOperationResult;
import com.matrix.agent.api.conversation.ConversationPage;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.conversation.CreateConversationRequest;
import com.matrix.agent.api.conversation.IConversationCallback;
import com.matrix.agent.api.conversation.IConversationService;
import com.matrix.agent.api.conversation.SendTextRequest;
import com.matrix.agent.conversation.CapabilityTraceCodec;
import com.matrix.agent.conversation.ConversationCoordinator;
import com.matrix.agent.conversation.ConversationDraftStore;
import com.matrix.agent.conversation.ConversationIds;
import com.matrix.agent.conversation.ConversationReadbackService;
import com.matrix.agent.conversation.ConversationRuntimeStageRegistry;
import com.matrix.agent.conversation.ConversationServiceGate;
import com.matrix.agent.conversation.ConversationStore;
import com.matrix.agent.conversation.ConversationVoiceBindingStore;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.task.durable.PersistenceGate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话域 Binder 门面。Binder 线程只做：调用方验证、输入校验、归属检查、
 * 域协调器调用与事件分发；永不执行模型/网络/阻塞等待。
 *
 * <p>恢复对账完成前（ConversationServiceGate）与 SQLCipher 不可用时
 * （PersistenceGate）全部 fail-closed：读返回空、写返回稳定错误码、
 * createConversation 直接拒绝——不返回半套对话状态（设计文档 §8.1/§5.3）。</p>
 */
public final class ConversationServiceStub extends IConversationService.Stub
        implements ConversationCoordinator.Listener {

    /** 订阅注册后先回放的有界快照条数（设计文档 §8.2）。 */
    static final int SUBSCRIPTION_SNAPSHOT_LIMIT = 50;
    /** 与任务域共用的同车仲裁键；对话任务的调度抢占语义与其它入口一致。 */
    private static final String ARBITRATION_KEY = "demo-vehicle";
    /** 现阶段 Host 将全部 Binder 调用者投影为 DRIVER 域；接入真实乘员身份后替换。 */
    private static final String CALLER_ZONE = "DRIVER";

    private final ConversationCoordinator coordinator;
    private final ConversationServiceGate recoveryGate;
    private final PersistenceGate persistenceGate;
    private final ModelServiceStub.CallerResolver callerResolver;
    private final ConversationVoiceBindingStore bindingStore;
    private final Map<String, CallbackRegistry<IConversationCallback>> subscriptions =
            new HashMap<>();
    private final Object subscribeLock = new Object();

    private final ConversationReadbackService readback;
    private final com.matrix.agent.conversation.ConversationSummaryMarker summaryMarker;
    /** 草稿端口（I4）；null = 降级（草稿方法 fail-closed）。 */
    private final ConversationDraftStore draftStore;
    /** 运行阶段注册表（I3）；null = 不推送阶段。 */
    private final ConversationRuntimeStageRegistry stageRegistry;
    /** 附件 staging（I6）；null = 降级（消息 DTO 不投影 chips）。 */
    private final com.matrix.agent.attachment.RoomAttachmentStagingStore attachmentStore;

    public ConversationServiceStub(ConversationCoordinator coordinator,
            ConversationServiceGate recoveryGate, PersistenceGate persistenceGate,
            ModelServiceStub.CallerResolver callerResolver,
            ConversationVoiceBindingStore bindingStore) {
        this(coordinator, recoveryGate, persistenceGate, callerResolver, bindingStore,
                null, null);
    }

    public ConversationServiceStub(ConversationCoordinator coordinator,
            ConversationServiceGate recoveryGate, PersistenceGate persistenceGate,
            ModelServiceStub.CallerResolver callerResolver,
            ConversationVoiceBindingStore bindingStore, ConversationReadbackService readback,
            com.matrix.agent.conversation.ConversationSummaryMarker summaryMarker) {
        this(coordinator, recoveryGate, persistenceGate, callerResolver, bindingStore,
                readback, summaryMarker, null, null);
    }

    public ConversationServiceStub(ConversationCoordinator coordinator,
            ConversationServiceGate recoveryGate, PersistenceGate persistenceGate,
            ModelServiceStub.CallerResolver callerResolver,
            ConversationVoiceBindingStore bindingStore, ConversationReadbackService readback,
            com.matrix.agent.conversation.ConversationSummaryMarker summaryMarker,
            ConversationDraftStore draftStore, ConversationRuntimeStageRegistry stageRegistry) {
        this(coordinator, recoveryGate, persistenceGate, callerResolver, bindingStore,
                readback, summaryMarker, draftStore, stageRegistry, null);
    }

    public ConversationServiceStub(ConversationCoordinator coordinator,
            ConversationServiceGate recoveryGate, PersistenceGate persistenceGate,
            ModelServiceStub.CallerResolver callerResolver,
            ConversationVoiceBindingStore bindingStore, ConversationReadbackService readback,
            com.matrix.agent.conversation.ConversationSummaryMarker summaryMarker,
            ConversationDraftStore draftStore, ConversationRuntimeStageRegistry stageRegistry,
            com.matrix.agent.attachment.RoomAttachmentStagingStore attachmentStore) {
        this.coordinator = coordinator;
        this.recoveryGate = recoveryGate;
        this.persistenceGate = persistenceGate;
        this.callerResolver = callerResolver;
        this.bindingStore = bindingStore;
        this.readback = readback;
        this.summaryMarker = summaryMarker;
        this.draftStore = draftStore;
        this.stageRegistry = stageRegistry;
        this.attachmentStore = attachmentStore;
        coordinator.setListener(this);
        // 阶段事件 → Binder 分发（I3）。cleared 不出 wire：终态消息 upsert 替代运行状态。
        if (stageRegistry != null) {
            stageRegistry.setListener(new ConversationRuntimeStageRegistry.Listener() {
                @Override
                public void onRuntimeStage(
                        ConversationRuntimeStageRegistry.StageEvent event) {
                    ConversationRuntimeStage dto = toStageDto(event);
                    registryOf(event.conversationId(), registry -> registry.dispatch(
                            callback -> callback.onRuntimeStageChanged(dto)));
                }
            });
        }
    }

    // ---------------------------------------------------------------- 域事件 → Binder 分发

    @Override
    public void onMessageUpsert(ConversationStore.MessageRow row) {
        ConversationMessage message = toDto(row);
        registryOf(row.conversationId(), registry -> registry.dispatch(
                callback -> callback.onMessageUpsert(message)));
    }

    @Override
    public void onMessageStatusChanged(String conversationId, String messageId,
            int statusWire, int failureCode) {
        registryOf(conversationId, registry -> registry.dispatch(
                callback -> callback.onMessageStatusChanged(conversationId, messageId,
                        statusWire, failureCode)));
    }

    /** 自动标题等展示元数据已持久化后的增量通知；不复用消息状态事件。 */
    public void onConversationInfoChanged(ConversationStore.ConversationRow row) {
        if (row == null) return;
        ConversationInfo info = toInfoDto(row);
        registryOf(row.conversationId(), registry -> registry.dispatch(
                callback -> callback.onConversationInfoChanged(info)));
    }

    // ---------------------------------------------------------------- Binder 实现

    @Override
    public ConversationInfo createConversation(CreateConversationRequest request,
            String clientOperationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(clientOperationId);
        if (request == null || request.schemaVersion > ParcelSchema.CURRENT) {
            throw new IllegalArgumentException("invalid create request");
        }
        requireAvailableForMutation();
        String title = HostInputValidator.boundUtf8(
                request.title == null ? "" : request.title.strip(), 120);
        return toInfoDto(coordinator.createConversation(ActorUsers.USER_DRIVER, CALLER_ZONE,
                title));
    }

    @Override
    public List<ConversationInfo> listConversations(ConversationListQuery query) {
        callerResolver.caller();
        if (query != null && query.schemaVersion > ParcelSchema.CURRENT) {
            throw new IllegalArgumentException("invalid query");
        }
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return Collections.emptyList();
        }
        boolean includeArchived = query != null && query.includeArchived;
        int limit = query == null ? ConversationListQuery.DEFAULT_LIMIT
                : Math.max(1, Math.min(query.limit, ConversationListQuery.MAX_LIMIT));
        List<ConversationStore.ConversationRow> rows =
                coordinator.listConversations(ActorUsers.USER_DRIVER, includeArchived, limit);
        List<ConversationInfo> result = new ArrayList<>(rows.size());
        for (ConversationStore.ConversationRow row : rows) {
            result.add(toInfoDto(row));
        }
        return result;
    }

    @Override
    public ConversationPage getMessages(String conversationId, long beforeSequenceExclusive,
            int limit) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return new ConversationPage(Collections.emptyList(), false);
        }
        requireOwnedConversation(conversationId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        ConversationStore.MessagePage page = coordinator.pageMessages(conversationId,
                beforeSequenceExclusive, boundedLimit);
        List<ConversationMessage> messages = new ArrayList<>(page.messagesAscending().size());
        for (ConversationStore.MessageRow row : page.messagesAscending()) {
            messages.add(toDto(row));
        }
        return new ConversationPage(messages, page.hasMore());
    }

    @Override
    public ConversationPage getMessagesAfter(String conversationId, long afterSequenceExclusive,
            int limit) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return new ConversationPage(ParcelSchema.CURRENT, Collections.emptyList(), false, false,
                    false, false);
        }
        requireOwnedConversation(conversationId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        ConversationStore.MessageWindow window = coordinator.windowAfter(conversationId,
                afterSequenceExclusive, boundedLimit);
        return toWindowDto(window);
    }

    @Override
    public ConversationPage getMessagesAround(String conversationId, long anchorSequence,
            int limit) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return new ConversationPage(ParcelSchema.CURRENT, Collections.emptyList(), false, false,
                    false, false);
        }
        requireOwnedConversation(conversationId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        // 不可定位（越权/被清理/不存在）统一空页 + anchorExists=false，不泄漏存在性
        ConversationStore.MessageWindow window = coordinator.windowAround(conversationId,
                anchorSequence, boundedLimit);
        return toWindowDto(window);
    }

    @Override
    public ConversationOperationResult renameConversation(String conversationId, String title,
            String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        String safeTitle = HostInputValidator.boundUtf8(title, 120);
        int availability = availabilityError();
        if (availability != MatrixErrorCode.SUCCESS) {
            return new ConversationOperationResult(availability, safeOperation, conversationId,
                    null);
        }
        requireOwnedConversation(conversationId);
        boolean renamed;
        try {
            renamed = coordinator.renameConversation(conversationId, safeTitle);
        } catch (IllegalArgumentException invalid) {
            return new ConversationOperationResult(MatrixErrorCode.INVALID_ARGUMENT,
                    safeOperation, conversationId, null);
        }
        return new ConversationOperationResult(
                renamed ? MatrixErrorCode.SUCCESS : MatrixErrorCode.NOT_FOUND,
                safeOperation, conversationId, null);
    }

    @Override
    public ConversationSubmission sendText(SendTextRequest request, String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        if (request == null || request.schemaVersion > ParcelSchema.CURRENT
                || request.conversationId == null) {
            throw new IllegalArgumentException("invalid send request");
        }
        int availability = availabilityError();
        if (availability != MatrixErrorCode.SUCCESS) {
            return new ConversationSubmission(availability, request.conversationId, null,
                    null, 0L, false);
        }
        requireOwnedConversation(request.conversationId);
        try {
            ConversationCoordinator.TextAccepted accepted = coordinator.submitText(
                    new ConversationCoordinator.TextCommand(
                            request.conversationId, request.text, request.languageTag,
                            safeOperation, Actor.DRIVER,
                            ConversationIds.agentSessionId(request.conversationId,
                                    Actor.DRIVER.name(), CALLER_ZONE),
                            ARBITRATION_KEY, null, null, request.quotedMessageId));
            return new ConversationSubmission(MatrixErrorCode.SUCCESS, request.conversationId,
                    accepted.userMessageId(), accepted.conversationTaskId(),
                    accepted.sequenceNo(), accepted.replay());
        } catch (IllegalArgumentException invalid) {
            return new ConversationSubmission(MatrixErrorCode.INVALID_ARGUMENT,
                    request.conversationId, null, null, 0L, false);
        }
    }

    @Override
    public ConversationOperationResult appendMessage(String conversationId, String text,
            String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        int availability = availabilityError();
        if (availability != MatrixErrorCode.SUCCESS) {
            return new ConversationOperationResult(availability, safeOperation, conversationId,
                    null);
        }
        requireOwnedConversation(conversationId);
        ConversationCoordinator.SteerAccepted accepted;
        try {
            // clientOperationId 透传协调器（评估 v1.0 §4.3）：幂等键 steer: 前缀派生，
            // Binder 重试命中既有行不重复投递——旧路径在此参数丢失。
            accepted = coordinator.appendSteer(conversationId, text, safeOperation);
        } catch (IllegalArgumentException invalid) {
            return new ConversationOperationResult(MatrixErrorCode.INVALID_ARGUMENT,
                    safeOperation, conversationId, null);
        }
        if (accepted == null) {
            return new ConversationOperationResult(MatrixErrorCode.INVALID_STATE,
                    safeOperation, conversationId, null);
        }
        return new ConversationOperationResult(MatrixErrorCode.SUCCESS,
                safeOperation, conversationId, accepted.steerMessageId());
    }

    @Override
    public ConversationSubmission submitTextOrAppend(String conversationId, String text,
            List<String> contextAttachmentIds, String draftInstanceId, long draftRevision,
            String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (draftInstanceId != null && !ConversationIds.isLowerUuid(draftInstanceId)) {
            return new ConversationSubmission(MatrixErrorCode.INVALID_ARGUMENT, conversationId,
                    null, null, 0L, false, ConversationSubmission.OUTCOME_REJECTED);
        }
        if (draftRevision < 0L) {
            return new ConversationSubmission(MatrixErrorCode.INVALID_ARGUMENT, conversationId,
                    null, null, 0L, false, ConversationSubmission.OUTCOME_REJECTED);
        }
        int availability = availabilityError();
        if (availability != MatrixErrorCode.SUCCESS) {
            return new ConversationSubmission(availability, conversationId, null, null, 0L,
                    false);
        }
        requireOwnedConversation(conversationId);
        try {
            // 附件（I6 §3.2/§9.2）：Phase 2A 起非空列表放行——owner/zone/READY/超量
            // 校验与受限文本投影全部在 Coordinator 的 AttachmentPort 内完成，且必须在
            // 任何持久化动作之前（整次拒绝，不留半写）；FAILED 附件在此以
            // INVALID_ARGUMENT + OUTCOME_REJECTED 返回。
            ConversationCoordinator.UnifiedTextOutcome outcome = coordinator.submitTextOrAppend(
                    new ConversationCoordinator.TextCommand(
                            conversationId, text, null, safeOperation, Actor.DRIVER,
                            ConversationIds.agentSessionId(conversationId,
                                    Actor.DRIVER.name(), CALLER_ZONE),
                            ARBITRATION_KEY, null, null, null, draftInstanceId,
                            contextAttachmentIds));
            int code = outcome.outcome()
                    == ConversationSubmission.OUTCOME_STEER_DELIVERY_FAILED
                    ? MatrixErrorCode.INVALID_STATE : MatrixErrorCode.SUCCESS;
            return new ConversationSubmission(code, conversationId,
                    outcome.userMessageId(), outcome.conversationTaskId(),
                    outcome.sequenceNo(), outcome.replay(), outcome.outcome());
        } catch (IllegalArgumentException invalid) {
            return new ConversationSubmission(MatrixErrorCode.INVALID_ARGUMENT, conversationId,
                    null, null, 0L, false, ConversationSubmission.OUTCOME_REJECTED);
        }
    }

    // ---------------------------------------------------------------- 草稿（I4）

    @Override
    public ConversationDraft getDraft(String conversationId) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (draftStore == null || availabilityError() != MatrixErrorCode.SUCCESS) {
            return null;
        }
        ConversationStore.ConversationRow conversation = requireOwnedConversation(conversationId);
        ConversationDraftStore.DraftRow row = draftStore.get(conversation.ownerUserId(),
                conversation.vehicleZone(), conversationId);
        return row == null ? null : new ConversationDraft(conversationId,
                row.draftInstanceId(), row.revision(), row.text(), row.selectionStart(),
                row.selectionEnd(), row.updatedAtMs());
    }

    @Override
    public int saveDraft(ConversationDraft draft) {
        callerResolver.caller();
        if (draft == null || draft.conversationId == null) {
            throw new IllegalArgumentException("invalid draft");
        }
        ConversationIds.requireLowerUuid(draft.conversationId, "conversationId");
        if (draftStore == null) {
            return availabilityError() == MatrixErrorCode.SUCCESS
                    ? MatrixErrorCode.INVALID_STATE : availabilityError();
        }
        requireAvailableForMutation();
        ConversationStore.ConversationRow conversation =
                requireOwnedConversation(draft.conversationId);
        ConversationDraftStore.SaveResult result = draftStore.save(
                new ConversationDraftStore.SaveCommand(
                        conversation.ownerUserId(), conversation.vehicleZone(),
                        draft.conversationId, draft.draftInstanceId, draft.revision,
                        draft.text, draft.selectionStart, draft.selectionEnd,
                        System.currentTimeMillis()));
        return switch (result) {
            case SAVED, IDEMPOTENT_STALE_REVISION -> MatrixErrorCode.SUCCESS;
            // 已消费 instance：客户端必须生成新 instance 再存，不得原样重试。
            case REJECTED_CONSUMED_INSTANCE -> MatrixErrorCode.INVALID_STATE;
            case REJECTED_PAYLOAD -> MatrixErrorCode.INVALID_ARGUMENT;
        };
    }

    @Override
    public void discardDraft(String conversationId, String draftInstanceId, long revision) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (draftStore == null) {
            return;
        }
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return;
        }
        ConversationStore.ConversationRow conversation = requireOwnedConversation(conversationId);
        draftStore.discard(conversation.ownerUserId(), conversation.vehicleZone(),
                conversationId, draftInstanceId);
    }

    @Override
    public ConversationOperationResult cancelMessage(String conversationId, String messageId,
            String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        int availability = availabilityError();
        if (availability != MatrixErrorCode.SUCCESS) {
            return new ConversationOperationResult(availability, safeOperation, conversationId,
                    null);
        }
        requireOwnedConversation(conversationId);
        if (messageId == null || !ConversationIds.isLowerUuid(messageId)) {
            throw new IllegalArgumentException("invalid messageId");
        }
        boolean accepted = coordinator.cancelByUserMessage(conversationId, messageId);
        return new ConversationOperationResult(
                accepted ? MatrixErrorCode.SUCCESS : MatrixErrorCode.NOT_FOUND,
                safeOperation, conversationId, messageId);
    }

    @Override
    public String createVoiceBinding(String conversationId, String clientOperationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        requireAvailableForMutation();
        requireOwnedConversation(conversationId);
        return bindingStore.create(conversationId, ActorUsers.USER_DRIVER);
    }

    @Override
    public ConversationOperationResult annotateMessage(String conversationId, String messageId,
            boolean favorite, String userNote, String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        String safeNote = HostInputValidator.boundUtf8(userNote,
                2 * ConversationCoordinator.NOTE_MAX_CHARS);
        int availability = availabilityError();
        if (availability != MatrixErrorCode.SUCCESS) {
            return new ConversationOperationResult(availability, safeOperation, conversationId,
                    null);
        }
        requireOwnedConversation(conversationId);
        try {
            coordinator.annotateMessage(conversationId, messageId,
                    ActorUsers.USER_DRIVER, favorite, safeNote);
        } catch (IllegalArgumentException invalid) {
            return new ConversationOperationResult(MatrixErrorCode.INVALID_ARGUMENT,
                    safeOperation, conversationId, null);
        }
        return new ConversationOperationResult(MatrixErrorCode.SUCCESS, safeOperation,
                conversationId, null);
    }

    @Override
    public String forkConversation(String parentConversationId, long atSequenceNo,
            String clientOperationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(parentConversationId, "parentConversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return null;
        }
        requireOwnedConversation(parentConversationId);
        try {
            return coordinator.forkFrom(parentConversationId, atSequenceNo,
                    ActorUsers.USER_DRIVER);
        } catch (IllegalArgumentException invalid) {
            return null; // 切点非完成消息/会话不存在：统一 null，不泄漏存在性
        }
    }

    @Override
    public String getLineageSummary(String childConversationId) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(childConversationId, "childConversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return null;
        }
        requireOwnedConversation(childConversationId);
        ConversationStore.LineageRow lineage = coordinator.lineageOf(childConversationId);
        if (lineage == null) {
            return null;
        }
        if (lineage.parentConversationId() == null) {
            return "来源已清除";
        }
        return lineage.parentTitleAtFork() == null || lineage.parentTitleAtFork().isBlank()
                ? "来自另一段对话" : "来自“" + lineage.parentTitleAtFork() + "”";
    }

    @Override
    public ConversationOperationResult speakAssistantMessage(String conversationId,
            String assistantMessageId, String clientOperationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(clientOperationId);
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return new ConversationOperationResult(availabilityError(), safeOperation,
                    conversationId, null);
        }
        requireOwnedConversation(conversationId);
        if (readback == null) {
            return new ConversationOperationResult(
                    MatrixErrorCode.VOICE_OUTPUT_UNAVAILABLE, safeOperation,
                    conversationId, null);
        }
        com.matrix.agent.voice.VoiceRuntime runtime =
                com.matrix.agent.voice.VoiceRuntimeHolder.get();
        if (runtime != null && runtime.isSessionActive()) {
            return new ConversationOperationResult(MatrixErrorCode.INVALID_STATE,
                    safeOperation, conversationId, null);
        }
        String error = readback.speak(conversationId, assistantMessageId,
                ActorUsers.USER_DRIVER);
        if (error == null) {
            return new ConversationOperationResult(MatrixErrorCode.SUCCESS, safeOperation,
                    conversationId, null);
        }
        switch (error) {
            case ConversationReadbackService.ERR_VOICE_ACTIVE:
                return new ConversationOperationResult(MatrixErrorCode.INVALID_STATE,
                        safeOperation, conversationId, null);
            case ConversationReadbackService.ERR_NOT_READABLE:
                return new ConversationOperationResult(MatrixErrorCode.INVALID_ARGUMENT,
                        safeOperation, conversationId, null);
            default:
                return new ConversationOperationResult(MatrixErrorCode.NOT_FOUND,
                        safeOperation, conversationId, null);
        }
    }

    @Override
    public boolean wouldSummarizeOnNextRound(String conversationId) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (availabilityError() != MatrixErrorCode.SUCCESS) {
            return false;
        }
        requireOwnedConversation(conversationId);
        return summaryMarker != null
                && summaryMarker.wouldSummarizeOnNextRound(conversationId);
    }

    @Override
    public void subscribeConversation(String conversationId,
            IConversationCallback callback) throws RemoteException {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (callback == null) throw new IllegalArgumentException("callback required");
        requireOwnedConversation(conversationId);
        synchronized (subscribeLock) {
            subscriptions.computeIfAbsent(conversationId, ignored -> new CallbackRegistry<>())
                    .add(callback);
        }
        // 有界快照先行（升序 upsert），再增量；客户端按 sequence 合并，可容忍重复。
        for (ConversationStore.MessageRow row
                : coordinator.latestMessages(conversationId, SUBSCRIPTION_SNAPSHOT_LIMIT)) {
            try {
                callback.onMessageUpsert(toDto(row));
            } catch (RemoteException dead) {
                unsubscribeConversation(callback);
                throw dead;
            }
        }
        // 运行阶段当前快照（I3 §6.2）：订阅受理后立即重发一次（snapshot=true），
        // 不补历史——重连方拿到的就是“此刻正在做什么”。无活跃任务不发送。
        if (stageRegistry != null) {
            ConversationRuntimeStageRegistry.StageEvent stage =
                    stageRegistry.snapshotOf(conversationId);
            if (stage != null) {
                try {
                    callback.onRuntimeStageChanged(toStageDto(stage));
                } catch (RemoteException dead) {
                    unsubscribeConversation(callback);
                    throw dead;
                }
            }
        }
    }

    @Override
    public void unsubscribeConversation(IConversationCallback callback) {
        callerResolver.caller();
        if (callback == null) return;
        synchronized (subscribeLock) {
            for (CallbackRegistry<IConversationCallback> registry : subscriptions.values()) {
                registry.remove(callback);
            }
        }
    }

    /** 域关闭路径：清空全部订阅（Binder death 之外的显式回收）。 */
    public void shutdown() {
        synchronized (subscribeLock) {
            for (CallbackRegistry<IConversationCallback> registry : subscriptions.values()) {
                registry.clear();
            }
            subscriptions.clear();
        }
    }

    // ---------------------------------------------------------------- 内部

    private int availabilityError() {
        if (!persistenceGate.isAvailable()) {
            return MatrixErrorCode.PERSISTENCE_UNAVAILABLE;
        }
        return recoveryGate.isAvailable() ? MatrixErrorCode.SUCCESS
                : MatrixErrorCode.SERVICE_NOT_READY;
    }

    /** 变更类入口的 fail-closed：不可用直接拒绝，不构造半套线程。 */
    private void requireAvailableForMutation() {
        int code = availabilityError();
        if (code != MatrixErrorCode.SUCCESS) {
            throw new IllegalStateException("conversation domain unavailable: " + code);
        }
    }

    /** 归属检查：会话必须存在且 owner 为该调用方用户的 Host 推导投影。 */
    private ConversationStore.ConversationRow requireOwnedConversation(String conversationId) {
        ConversationStore.ConversationRow row =
                coordinator.store().findConversation(conversationId);
        if (row == null) {
            throw new IllegalArgumentException("conversation 不存在");
        }
        if (!ActorUsers.USER_DRIVER.equals(row.ownerUserId())) {
            // 多用户投影接入前，Host 只服务 driver 域；越权读取直接拒绝。
            throw new SecurityException("conversation 不属于该调用方");
        }
        return row;
    }

    private interface RegistryAction {
        void run(CallbackRegistry<IConversationCallback> registry);
    }

    private void registryOf(String conversationId, RegistryAction action) {
        CallbackRegistry<IConversationCallback> registry;
        synchronized (subscribeLock) {
            registry = subscriptions.get(conversationId);
        }
        if (registry != null) {
            action.run(registry);
        }
    }

    private ConversationPage toWindowDto(ConversationStore.MessageWindow window) {
        List<ConversationMessage> messages =
                new ArrayList<>(window.messagesAscending().size());
        for (ConversationStore.MessageRow row : window.messagesAscending()) {
            messages.add(toDto(row));
        }
        return new ConversationPage(ParcelSchema.CURRENT, messages,
                window.hasBefore(), window.hasBefore(), window.hasAfter(),
                window.anchorExists());
    }

    private static ConversationRuntimeStage toStageDto(
            ConversationRuntimeStageRegistry.StageEvent event) {
        return new ConversationRuntimeStage(event.conversationId(),
                event.conversationTaskId(), event.generation(), event.stage(),
                event.safeLabel(), event.occurredAtMs(), event.snapshot());
    }

    private static ConversationInfo toInfoDto(ConversationStore.ConversationRow row) {
        return new ConversationInfo(ParcelSchema.CURRENT, row.conversationId(), row.title(),
                row.ownerUserId(), row.vehicleZone(), row.archived(), row.createdAtMs(),
                row.updatedAtMs(), row.titleOrigin(), row.pinned(), row.lastInputChannel());
    }

    private ConversationMessage toDto(ConversationStore.MessageRow row) {
        // 轨迹只挂用户消息（经 TaskLink.user_message_id 关联，评估 v1.0 §4.3）；
        // 解码失败 fail-closed 为空列表——展示层绝不因投影损坏而炸
        java.util.List<CapabilityTraceEntry> traces = java.util.Collections.emptyList();
        if (row.conversationTaskId() != null
                && row.roleWire() == ConversationMessage.ROLE_USER) {
            String traceJson = coordinator.traceJsonOf(row.conversationTaskId());
            if (traceJson != null) {
                try {
                    traces = CapabilityTraceCodec.decode(traceJson).stream()
                            .map(trace -> new CapabilityTraceEntry(trace.capabilityId,
                                    trace.friendlyName, trace.outcome,
                                    trace.requestedDisplay, trace.verifiedDisplay,
                                    trace.verificationState))
                            .collect(java.util.stream.Collectors.toList());
                } catch (IllegalArgumentException malformed) {
                    android.util.Log.w("MatrixAgent",
                            "[Conversation] 轨迹投影畸形，降级为空 task="
                                    + row.conversationTaskId());
                }
            }
        }
        // 附件 chips（I6 v8）：只挂 INPUT_PRIMARY 用户消息（steer 无附件）；
        // 投影只含 chip 元数据，正文（extractedText）不跨 Binder（§9.2）。
        java.util.List<com.matrix.agent.api.conversation.ConversationAttachment>
                attachments = java.util.Collections.emptyList();
        if (row.roleWire() == ConversationMessage.ROLE_USER
                && row.inputKindWire() == ConversationMessage.INPUT_PRIMARY
                && attachmentStore != null) {
            attachments = com.matrix.agent.attachment.RoomAttachmentStagingStore
                    .toDtoList(attachmentStore.listByMessage(row.messageId()));
        }
        return new ConversationMessage(ParcelSchema.CURRENT, row.conversationId(),
                row.messageId(), row.sequenceNo(), row.roleWire(), row.statusWire(),
                row.channelWire(), row.text(), row.languageTag(), row.conversationTaskId(),
                row.failureCode(), row.createdAtMs(), row.updatedAtMs(),
                row.inputKindWire(), row.steerHostUserMessageId(), row.steerDeliveryWire(),
                traces, attachments);
    }
}
