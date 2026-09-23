package com.matrix.agent.client;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.conversation.ConversationAttachment;
import com.matrix.agent.api.conversation.IConversationAttachmentService;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 附件域 Manager（输入交互增强 I6，v8）：PFD staging / 草稿附件清单 / 删除。
 *
 * <p>调用方只打开只读 {@link ParcelFileDescriptor} 交给 Host——URI 授权与文件路径
 * 都不跨 Binder 假定。断线后方法返回与 {@code getState()} 一致的稳定不可用结果
 * （null / 空列表 / SERVICE_NOT_READY），重连由门面自动换绑 proxy。</p>
 */
public final class AttachmentManager extends MatrixManagerBase {

    private volatile IConversationAttachmentService service;

    AttachmentManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IConversationAttachmentService.Stub.asInterface(serviceBinder);
    }

    /** 摄取附件；不可用或传输失败返回 state=FAILED 的占位（错误码 SERVICE_NOT_READY）。 */
    public ConversationAttachment stage(String conversationId,
            ParcelFileDescriptor fd, String declaredMime, String displayName,
            String clientOperationId) {
        Objects.requireNonNull(fd, "fd");
        IConversationAttachmentService s = service;
        if (s == null) return unavailable(conversationId);
        try {
            ConversationAttachment result = s.stage(conversationId, fd, declaredMime,
                    displayName, clientOperationId);
            return result == null ? unavailable(conversationId) : result;
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(conversationId));
        }
    }

    public List<ConversationAttachment> listDraftAttachments(String conversationId) {
        IConversationAttachmentService s = service;
        if (s == null) return Collections.emptyList();
        try {
            List<ConversationAttachment> result = s.listDraftAttachments(conversationId);
            return result == null ? Collections.emptyList() : result;
        } catch (RemoteException e) {
            return handleRemoteException(e, Collections.emptyList());
        }
    }

    public int deleteAttachment(String attachmentId, String clientOperationId) {
        IConversationAttachmentService s = service;
        if (s == null) return MatrixErrorCode.SERVICE_NOT_READY;
        try {
            return s.deleteAttachment(attachmentId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, MatrixErrorCode.SERVICE_NOT_READY);
        }
    }

    private static ConversationAttachment unavailable(String conversationId) {
        return new ConversationAttachment(null, conversationId, null, null, 0L,
                ConversationAttachment.STATE_FAILED, MatrixErrorCode.SERVICE_NOT_READY,
                0, 0L);
    }

    @Override protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override protected void onMatrixServiceConnected(IBinder serviceBinder) {
        service = IConversationAttachmentService.Stub.asInterface(serviceBinder);
    }
}
