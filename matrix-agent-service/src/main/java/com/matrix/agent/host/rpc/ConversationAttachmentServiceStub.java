package com.matrix.agent.host.rpc;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.conversation.ConversationAttachment;
import com.matrix.agent.api.conversation.IConversationAttachmentService;
import com.matrix.agent.attachment.RoomAttachmentStagingStore;
import com.matrix.agent.conversation.ConversationIds;
import com.matrix.agent.conversation.ConversationStore;
import com.matrix.agent.data.conversation.ConversationAttachmentEntity;
import com.matrix.agent.task.durable.PersistenceGate;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 附件域 Binder 门面（I6 §9.2）。Binder 线程只做验证、短事务创建 STAGING 行与派发；
 * fd 读取和文本提取完全在 Host IO executor。客户端通过 listDraftAttachments 观察
 * STAGING → READY/FAILED，因而单个慢文件不会占住系统 Binder worker。
 *
 * <p>fail-closed：SQLCipher 不可用（PersistenceGate）时全部方法返回稳定错误；
 * 执行器满返回 OVERLOADED（不排队烧 fd 的生存期）。提取失败以 FAILED 行返回
 * （chip 可见原因），不向客户端抛异常。</p>
 */
public final class ConversationAttachmentServiceStub
        extends IConversationAttachmentService.Stub {

    private static final String TAG = "MatrixAgent";
    private final RoomAttachmentStagingStore store;
    private final ConversationStore conversations;
    private final PersistenceGate persistenceGate;
    private final ModelServiceStub.CallerResolver callerResolver;
    private final ExecutorService ioExecutor;

    public ConversationAttachmentServiceStub(RoomAttachmentStagingStore store,
            ConversationStore conversations, PersistenceGate persistenceGate,
            ModelServiceStub.CallerResolver callerResolver, ExecutorService ioExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.persistenceGate = Objects.requireNonNull(persistenceGate, "persistenceGate");
        this.callerResolver = Objects.requireNonNull(callerResolver, "callerResolver");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    }

    @Override
    public ConversationAttachment stage(String conversationId, ParcelFileDescriptor fd,
            String declaredMime, String displayName, String clientOperationId) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        Objects.requireNonNull(fd, "fd");
        HostInputValidator.requireOperationId(clientOperationId);
        if (!persistenceGate.isAvailable()) {
            return failed(conversationId, MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
        }
        ConversationStore.ConversationRow conversation =
                requireOwnedConversation(conversationId);
        if (conversation == null) {
            return failed(conversationId, MatrixErrorCode.NOT_FOUND);
        }
        String safeName = HostInputValidator.boundUtf8(
                displayName == null ? "附件" : displayName, 120);
        RoomAttachmentStagingStore.StageStart started = store.beginStage(
                conversation.ownerUserId(), conversation.vehicleZone(), conversationId,
                declaredMime, safeName, clientOperationId);
        if (!started.created()) {
            // 相同 scope + operationId 是幂等重放：已有 READY/FAILED/STAGING 行均为权威，
            // 不重复读取 fd，也不因重放延长文件描述符生命周期。
            closeQuietly(fd);
            return RoomAttachmentStagingStore.toDto(started.attachment());
        }
        try {
            ioExecutor.execute(() -> {
                try (InputStream input = new FileInputStream(fd.getFileDescriptor())) {
                    store.completeStage(started.attachment().attachmentId, input, declaredMime);
                } catch (IOException transport) {
                    Log.w(TAG, "[Attachment] fd 读取失败 type="
                            + transport.getClass().getSimpleName());
                    store.failStage(started.attachment().attachmentId,
                            MatrixErrorCode.TASK_FAILED);
                } finally {
                    closeQuietly(fd);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            closeQuietly(fd);
            // executor 已拒绝，不能让 STAGING 永久停留；以可解释 FAILED 行回给 chip。
            store.failStage(started.attachment().attachmentId, MatrixErrorCode.OVERLOADED);
        }
        return RoomAttachmentStagingStore.toDto(started.attachment());
    }

    @Override
    public List<ConversationAttachment> listDraftAttachments(String conversationId) {
        callerResolver.caller();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (!persistenceGate.isAvailable()) {
            return List.of();
        }
        if (requireOwnedConversation(conversationId) == null) {
            return List.of();
        }
        List<ConversationAttachment> projected = new ArrayList<>();
        for (ConversationAttachmentEntity entity : store.listDraft(conversationId)) {
            projected.add(RoomAttachmentStagingStore.toDto(entity));
        }
        return projected;
    }

    @Override
    public int deleteAttachment(String attachmentId, String clientOperationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(clientOperationId);
        Objects.requireNonNull(attachmentId, "attachmentId");
        if (!persistenceGate.isAvailable()) {
            return MatrixErrorCode.PERSISTENCE_UNAVAILABLE;
        }
        // attachment id 本身不是授权。删除必须再次按 Host 推导的 owner/zone 约束，
        // 不能让同一受信 Binder 调用者凭一个 UUID 跨 scope 清掉别人的草稿附件。
        return store.deleteDraft(com.matrix.agent.identity.ActorUsers.USER_DRIVER, "DRIVER",
                attachmentId)
                ? MatrixErrorCode.SUCCESS : MatrixErrorCode.INVALID_STATE;
    }

    /** 归属检查：与对话域同口径（owner 必须是 caller 推导的 DRIVER 域）。 */
    private ConversationStore.ConversationRow requireOwnedConversation(String conversationId) {
        ConversationStore.ConversationRow row = conversations.findConversation(conversationId);
        if (row == null) {
            throw new IllegalArgumentException("conversation 不存在");
        }
        if (!com.matrix.agent.identity.ActorUsers.USER_DRIVER.equals(row.ownerUserId())) {
            throw new SecurityException("conversation 不属于该调用方");
        }
        return row;
    }

    private static ConversationAttachment failed(String conversationId, int errorCode) {
        return new ConversationAttachment(null, conversationId, null, null, 0L,
                ConversationAttachment.STATE_FAILED, errorCode, 0, 0L);
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException ignored) {
            // 两端谁先关闭都不影响 Host 对已复制 fd 的所有权。
        }
    }
}
