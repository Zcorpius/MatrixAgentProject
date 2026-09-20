package com.matrix.agent.conversation;

import android.util.Log;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.conversation.ConversationStore.InterruptedLink;

import java.util.List;
import java.util.Objects;

/**
 * 进程死亡后的对话对账（设计文档 §5.3；明确不复用 durable task）。
 *
 * <p>Host 启动、数据库可用且对外暴露会话前执行一次事务性收敛：
 * 只读快照为 true → FAILED/PROCESS_INTERRUPTED；false、缺失或不可信 →
 * EXECUTION_UNKNOWN（绝不把写操作的未知态谎报成失败或取消）。每条受影响线程写入一条
 * sequence 有序的 SYSTEM 说明行；重复对账幂等（link 已终态即跳过）。</p>
 */
public final class ConversationRecoveryCoordinator {

    private static final String TAG = "MatrixAgent";

    private final ConversationStore store;
    /** 自动标题触发（评估 v1.0 §4.1）：恢复收敛的 EXECUTION_UNKNOWN 同样算首轮终态。 */
    private final ConversationCoordinator.TerminalRoundSink titleSink;

    public ConversationRecoveryCoordinator(ConversationStore store,
            ConversationCoordinator.TerminalRoundSink titleSink) {
        this.store = Objects.requireNonNull(store, "store");
        this.titleSink = titleSink == null
                ? (conv, msg, status) -> { } : titleSink;
    }

    /** 收敛在途消息；返回受影响的消息数（幂等，重跑为 0）。 */
    public int recover() {
        List<InterruptedLink> interrupted = store.loadNonTerminalLinks();
        if (interrupted.isEmpty()) {
            return 0;
        }
        int recovered = 0;
        for (InterruptedLink link : interrupted) {
            PersistedMessageStatus status = link.readOnlyHint()
                    ? PersistedMessageStatus.FAILED
                    : PersistedMessageStatus.EXECUTION_UNKNOWN;
            store.writeRecoveryOutcome(link.conversationTaskId(), status.wire(),
                    MatrixErrorCode.PROCESS_INTERRUPTED);
            titleSink.onTerminalRound(link.conversationId(), link.userMessageId(),
                    status.wire());
            try {
                store.appendSystemNote(link.conversationId(),
                        link.readOnlyHint()
                                ? "Host 进程重启，该消息的任务已中断，未执行后续操作。"
                                : "Host 进程重启，该消息的指令可能已发出但执行结果未知；请查看设备当前状态。");
            } catch (RuntimeException noteFailure) {
                // 说明行缺失不阻塞收敛（消息终态已落）；记录即可。
                Log.w(TAG, "[ConversationRecovery] 说明行写入失败 conv="
                        + link.conversationId() + " cause="
                        + noteFailure.getClass().getSimpleName());
            }
            recovered++;
            Log.i(TAG, "[ConversationRecovery] task=" + link.conversationTaskId()
                    + " readOnly=" + link.readOnlyHint() + " -> " + status);
        }
        return recovered;
    }
}
