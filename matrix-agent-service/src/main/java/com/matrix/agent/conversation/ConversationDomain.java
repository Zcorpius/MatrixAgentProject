package com.matrix.agent.conversation;

/**
 * 对话域冻结枚举集中地（设计文档 §4.3）。
 *
 * <p>PersistedMessageStatus 是唯一允许写入 conversation_message.status 的状态集，
 * 与 SDK ConversationMessage.STATUS_* 冻结值一一对应；TransientInputState 仅存在于
 * Launcher/订阅内存投影，绝不入库。二者不可混用。</p>
 */
public final class ConversationDomain {
    private ConversationDomain() { }

    public enum ConversationInputChannel { TEXT, PTT, WAKE }

    public enum ConversationMessageRole { USER, ASSISTANT, SYSTEM }

    /** 仅能写入 conversation_message.status 的状态。 */
    public enum PersistedMessageStatus {
        ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, EXECUTION_UNKNOWN, REJECTED;

        public static PersistedMessageStatus fromWire(int wire) {
            return switch (wire) {
                case 0 -> ACCEPTED;
                case 1 -> RUNNING;
                case 2 -> COMPLETED;
                case 3 -> FAILED;
                case 4 -> CANCELLED;
                case 5 -> EXECUTION_UNKNOWN;
                case 6 -> REJECTED;
                default -> throw new IllegalArgumentException("未知消息状态 wire=" + wire);
            };
        }

        public int wire() {
            return ordinal();
        }

        public boolean isTerminal() {
            return this != ACCEPTED && this != RUNNING;
        }
    }

    /** 仅 Launcher/Host subscription 内存投影；绝不写入 conversation_message。 */
    public enum TransientInputState { DRAFT, TRANSCRIBING }
}
