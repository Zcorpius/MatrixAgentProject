package com.matrix.agent.conversation;

import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.conversation.ConversationStore.ConversationRow;
import com.matrix.agent.conversation.ConversationStore.InterruptedLink;
import com.matrix.agent.conversation.ConversationStore.MessagePage;
import com.matrix.agent.conversation.ConversationStore.MessageRow;
import com.matrix.agent.conversation.ConversationStore.SubmittedUserMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JVM 内存版 Store：复刻 Room 事务语义（幂等判重 / sequence 递增 / 终态丢弃已清线程）。 */
public final class FakeConversationStore implements ConversationStore {

    public final Map<String, ConversationRow> conversations = new LinkedHashMap<>();
    public final Map<String, MessageRow> messages = new LinkedHashMap<>();
    public final Map<String, String> idempotencyIndex = new HashMap<>();
    public final Map<String, LinkRow> links = new LinkedHashMap<>();
    public final List<String> systemNotes = new ArrayList<>();
    public int clearCalls;

    public static final class LinkRow {
        public final String conversationTaskId;
        public final String runtimeRequestId;
        public final String conversationId;
        public final String userMessageId;
        public final boolean readOnlyHint;
        public Integer terminalStatus;
        public Long startedAtMs;

        LinkRow(String conversationTaskId, String runtimeRequestId, String conversationId,
                String userMessageId, boolean readOnlyHint) {
            this.conversationTaskId = conversationTaskId;
            this.runtimeRequestId = runtimeRequestId;
            this.conversationId = conversationId;
            this.userMessageId = userMessageId;
            this.readOnlyHint = readOnlyHint;
        }
    }

    public ConversationRow seedConversation(String conversationId, String ownerUserId) {
        ConversationRow row = new ConversationRow(conversationId, ownerUserId, "DRIVER",
                null, false, 1L, 1L, 0, false, 0);
        conversations.put(conversationId, row);
        return row;
    }

    @Override
    public ConversationRow createConversation(NewConversation command) {
        ConversationRow row = new ConversationRow(command.conversationId(),
                command.ownerUserId(), command.vehicleZone(), command.title(), false,
                System.currentTimeMillis(), System.currentTimeMillis(),
                com.matrix.agent.api.conversation.ConversationInfo.TITLE_ORIGIN_DEFAULT,
                false, com.matrix.agent.api.conversation.ConversationMessage.CHANNEL_NONE);
        conversations.put(row.conversationId(), row);
        return row;
    }

    @Override
    public ConversationRow findConversation(String conversationId) {
        return conversations.get(conversationId);
    }

    @Override
    public List<ConversationRow> listConversations(String ownerUserId, boolean includeArchived,
            int limit) {
        List<ConversationRow> rows = new ArrayList<>();
        for (ConversationRow row : conversations.values()) {
            if (row.ownerUserId().equals(ownerUserId) && (includeArchived || !row.archived())) {
                rows.add(row);
            }
        }
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }

    @Override
    public MessageRow findMessage(String messageId) {
        return messages.get(messageId);
    }

    @Override
    public MessageRow findMessageByIdempotencyKey(String idempotencyKey) {
        String messageId = idempotencyIndex.get(idempotencyKey);
        return messageId == null ? null : messages.get(messageId);
    }

    @Override
    public List<MessageRow> latestMessages(String conversationId, int limit) {
        return ascending(conversationId, limit);
    }

    @Override
    public List<MessageRow> latestCompletedForSeed(String conversationId, int maxEntries) {
        List<MessageRow> completed = new ArrayList<>();
        for (MessageRow row : ascending(conversationId, Integer.MAX_VALUE)) {
            if (row.statusWire() == PersistedMessageStatus.COMPLETED.wire()
                    && (row.roleWire() == 0 || row.roleWire() == 1)) {
                completed.add(row);
            }
        }
        return completed.size() > maxEntries
                ? new ArrayList<>(completed.subList(completed.size() - maxEntries,
                        completed.size()))
                : completed;
    }

    @Override
    public MessagePage pageMessages(String conversationId, long beforeSequenceExclusive,
            int limit) {
        List<MessageRow> all = ascending(conversationId, Integer.MAX_VALUE);
        List<MessageRow> filtered = new ArrayList<>();
        for (MessageRow row : all) {
            if (beforeSequenceExclusive < 0 || row.sequenceNo() < beforeSequenceExclusive) {
                filtered.add(row);
            }
        }
        boolean hasMore = filtered.size() > limit;
        return new MessagePage(
                hasMore ? new ArrayList<>(filtered.subList(0, limit)) : filtered, hasMore);
    }

    @Override
    public synchronized SubmittedUserMessage submitUserMessage(UserSubmission command) {
        if (command.idempotencyKey() != null
                && idempotencyIndex.containsKey(command.idempotencyKey())) {
            return new SubmittedUserMessage(
                    messages.get(idempotencyIndex.get(command.idempotencyKey())).sequenceNo(),
                    true);
        }
        ConversationRow conversation = conversations.get(command.conversationId());
        if (conversation == null) {
            throw new IllegalArgumentException("conversation 不存在: " + command.conversationId());
        }
        if (conversation.archived()) {
            throw new IllegalArgumentException("conversation 已归档: " + command.conversationId());
        }
        long sequence = 1;
        for (MessageRow row : messages.values()) {
            if (row.conversationId().equals(command.conversationId())
                    && row.sequenceNo() >= sequence) {
                sequence = row.sequenceNo() + 1;
            }
        }
        long now = System.currentTimeMillis();
        messages.put(command.messageId(), new MessageRow(command.messageId(),
                command.conversationId(), sequence, ROLE_USER,
                PersistedMessageStatus.ACCEPTED.wire(), command.channelWire(), command.text(),
                command.languageTag(), command.conversationTaskId(), 0, now, now,
                MessageRow.INPUT_PRIMARY_WIRE, null, MessageRow.STEER_DELIVERY_NONE_WIRE));
        if (command.idempotencyKey() != null) {
            idempotencyIndex.put(command.idempotencyKey(), command.messageId());
        }
        links.put(command.conversationTaskId(), new LinkRow(command.conversationTaskId(),
                command.runtimeRequestId(), command.conversationId(), command.messageId(),
                command.readOnlyHint()));
        return new SubmittedUserMessage(sequence, false);
    }

    @Override
    public boolean markRunning(String conversationTaskId) {
        LinkRow link = links.get(conversationTaskId);
        if (link == null || link.terminalStatus != null) {
            return false;
        }
        link.startedAtMs = System.currentTimeMillis();
        MessageRow user = messages.get(link.userMessageId);
        messages.put(link.userMessageId, withStatus(user,
                PersistedMessageStatus.RUNNING.wire(), 0));
        return true;
    }

    @Override
    public SubmittedSteerMessage appendSteerMessage(SteerSubmission command) {
        if (command.idempotencyKey() != null
                && idempotencyIndex.containsKey(command.idempotencyKey())) {
            return new SubmittedSteerMessage(
                    messages.get(idempotencyIndex.get(command.idempotencyKey())).sequenceNo(),
                    true);
        }
        ConversationRow conversation = conversations.get(command.conversationId());
        if (conversation == null || conversation.archived()) {
            throw new IllegalArgumentException(
                    "conversation 不存在或已归档: " + command.conversationId());
        }
        LinkRow running = null;
        for (LinkRow link : links.values()) {
            if (link.conversationId.equals(command.conversationId())
                    && link.terminalStatus == null) {
                running = link;
                break;
            }
        }
        if (running == null || !running.userMessageId.equals(command.hostUserMessageId())) {
            throw new IllegalStateException("宿主任务不在运行中: " + command.conversationId());
        }
        long sequence = 1;
        for (MessageRow row : messages.values()) {
            if (row.conversationId().equals(command.conversationId())
                    && row.sequenceNo() >= sequence) {
                sequence = row.sequenceNo() + 1;
            }
        }
        long now = System.currentTimeMillis();
        messages.put(command.messageId(), new MessageRow(command.messageId(),
                command.conversationId(), sequence, ROLE_USER,
                PersistedMessageStatus.RUNNING.wire(), command.channelWire(), command.text(),
                command.languageTag(), null, 0, now, now,
                MessageRow.INPUT_STEER_WIRE, command.hostUserMessageId(),
                com.matrix.agent.api.conversation.ConversationMessage.STEER_DELIVERY_PENDING));
        if (command.idempotencyKey() != null) {
            idempotencyIndex.put(command.idempotencyKey(), command.messageId());
        }
        return new SubmittedSteerMessage(sequence, false);
    }

    @Override
    public void updateSteerDelivery(String messageId, boolean offered) {
        MessageRow row = messages.get(messageId);
        if (row == null || row.inputKindWire() != MessageRow.INPUT_STEER_WIRE
                || row.steerDeliveryWire()
                        != com.matrix.agent.api.conversation.ConversationMessage.STEER_DELIVERY_PENDING) {
            return; // 非 PENDING 行 no-op（迟到回执不覆盖已收敛事实）
        }
        int delivery = offered
                ? com.matrix.agent.api.conversation.ConversationMessage.STEER_DELIVERY_OFFERED
                : com.matrix.agent.api.conversation.ConversationMessage.STEER_DELIVERY_FAILED;
        int status = offered ? row.statusWire() : PersistedMessageStatus.FAILED.wire();
        messages.put(messageId, new MessageRow(row.messageId(), row.conversationId(),
                row.sequenceNo(), row.roleWire(), status, row.channelWire(), row.text(),
                row.languageTag(), row.conversationTaskId(), row.failureCode(),
                row.createdAtMs(), System.currentTimeMillis(), row.inputKindWire(),
                row.steerHostUserMessageId(), delivery));
    }

    @Override
    public boolean writeTerminal(TerminalWrite command) {
        LinkRow link = links.get(command.conversationTaskId());
        if (link == null) {
            return false;
        }
        if (!conversations.containsKey(link.conversationId)) {
            return false; // 清库后丢弃
        }
        long now = System.currentTimeMillis();
        MessageRow user = messages.get(link.userMessageId);
        messages.put(link.userMessageId,
                withStatus(user, command.userStatusWire(), command.failureCode()));
        // 附属 steer 镜像收敛（与 Room 实现同契约）：仅 RUNNING 行，投递态保留
        for (java.util.Map.Entry<String, MessageRow> entry : messages.entrySet()) {
            MessageRow row = entry.getValue();
            if (row.inputKindWire() == MessageRow.INPUT_STEER_WIRE
                    && link.userMessageId.equals(row.steerHostUserMessageId())
                    && row.statusWire() == PersistedMessageStatus.RUNNING.wire()) {
                entry.setValue(withStatus(row, command.userStatusWire(),
                        command.failureCode()));
            }
        }
        if (command.assistantText() != null && !command.assistantText().isBlank()) {
            long sequence = 1;
            for (MessageRow row : messages.values()) {
                if (row.conversationId().equals(link.conversationId)
                        && row.sequenceNo() >= sequence) {
                    sequence = row.sequenceNo() + 1;
                }
            }
            messages.put(command.assistantMessageId(), new MessageRow(
                    command.assistantMessageId(), link.conversationId, sequence, ROLE_ASSISTANT,
                    command.userStatusWire(), MessageRow.CHANNEL_NONE_WIRE,
                    command.assistantText(), null, command.conversationTaskId(),
                    command.failureCode(), now, now,
                    MessageRow.INPUT_PRIMARY_WIRE, null, MessageRow.STEER_DELIVERY_NONE_WIRE));
        }
        link.terminalStatus = command.userStatusWire();
        return true;
    }

    @Override
    public long appendSystemNote(String conversationId, String text) {
        long sequence = 1;
        for (MessageRow row : messages.values()) {
            if (row.conversationId().equals(conversationId) && row.sequenceNo() >= sequence) {
                sequence = row.sequenceNo() + 1;
            }
        }
        messages.put(java.util.UUID.randomUUID().toString(), new MessageRow(
                java.util.UUID.randomUUID().toString(), conversationId, sequence, ROLE_SYSTEM,
                PersistedMessageStatus.COMPLETED.wire(), MessageRow.CHANNEL_NONE_WIRE, text,
                null, null, 0, System.currentTimeMillis(), System.currentTimeMillis(),
                MessageRow.INPUT_PRIMARY_WIRE, null, MessageRow.STEER_DELIVERY_NONE_WIRE));
        systemNotes.add(text);
        return sequence;
    }

    @Override
    public List<InterruptedLink> loadNonTerminalLinks() {
        List<InterruptedLink> rows = new ArrayList<>();
        for (LinkRow link : links.values()) {
            if (link.terminalStatus == null) {
                rows.add(new InterruptedLink(link.conversationTaskId, link.conversationId,
                        link.userMessageId, link.readOnlyHint));
            }
        }
        return rows;
    }

    @Override
    public void writeRecoveryOutcome(String conversationTaskId, int userStatusWire,
            int failureCode) {
        LinkRow link = links.get(conversationTaskId);
        if (link == null || link.terminalStatus != null) {
            return;
        }
        MessageRow user = messages.get(link.userMessageId);
        messages.put(link.userMessageId,
                withStatus(user, userStatusWire, failureCode));
        link.terminalStatus = userStatusWire;
    }

    @Override
    public String findRunningTaskId(String conversationId) {
        for (LinkRow link : links.values()) {
            if (link.conversationId.equals(conversationId) && link.terminalStatus == null
                    && link.startedAtMs != null) {
                return link.conversationTaskId;
            }
        }
        return null;
    }

    @Override
    public boolean autoTitleIfDefault(String conversationId, String title) {
        ConversationRow row = conversations.get(conversationId);
        if (row == null
                || row.titleOrigin()
                        != com.matrix.agent.api.conversation.ConversationInfo.TITLE_ORIGIN_DEFAULT) {
            return false;
        }
        conversations.put(conversationId, new ConversationRow(row.conversationId(),
                row.ownerUserId(), row.vehicleZone(), title, row.archived(),
                row.createdAtMs(), System.currentTimeMillis(),
                com.matrix.agent.api.conversation.ConversationInfo.TITLE_ORIGIN_AUTO,
                row.pinned(), row.lastInputChannel()));
        return true;
    }

    @Override
    public boolean renameConversation(String conversationId, String title) {
        ConversationRow row = conversations.get(conversationId);
        if (row == null) {
            return false;
        }
        conversations.put(conversationId, new ConversationRow(row.conversationId(),
                row.ownerUserId(), row.vehicleZone(), title, row.archived(),
                row.createdAtMs(), System.currentTimeMillis(),
                com.matrix.agent.api.conversation.ConversationInfo.TITLE_ORIGIN_USER,
                row.pinned(), row.lastInputChannel()));
        return true;
    }

    @Override
    public MessageWindow windowAfter(String conversationId, long afterSequenceExclusive,
            int limit) {
        List<MessageRow> ascending = conversationRowsAscending(conversationId);
        List<MessageRow> result = new ArrayList<>();
        for (MessageRow row : ascending) {
            if (row.sequenceNo() > afterSequenceExclusive) {
                result.add(row);
            }
        }
        boolean hasAfter = result.size() > limit;
        List<MessageRow> page = hasAfter ? new ArrayList<>(result.subList(0, limit)) : result;
        boolean hasBefore = !ascending.isEmpty()
                && ascending.get(0).sequenceNo() < page.get(0).sequenceNo();
        return new MessageWindow(page, hasBefore, hasAfter, true);
    }

    @Override
    public MessageWindow windowAround(String conversationId, long anchorSequence, int limit) {
        List<MessageRow> ascending = conversationRowsAscending(conversationId);
        int anchorIndex = -1;
        for (int i = 0; i < ascending.size(); i++) {
            if (ascending.get(i).sequenceNo() == anchorSequence) {
                anchorIndex = i;
                break;
            }
        }
        if (anchorIndex < 0) {
            return MessageWindow.anchorMissing();
        }
        // 显式双侧窗口：锚点前 beforeCount 条 + 锚点（含）起 afterCount 条
        int beforeCount = limit / 2;
        int afterCount = limit - beforeCount;
        int from = Math.max(0, anchorIndex - beforeCount);
        int to = Math.min(ascending.size(), anchorIndex + 1 + afterCount);
        List<MessageRow> window = new ArrayList<>(ascending.subList(from, to));
        boolean hasBefore = from > 0;
        boolean hasAfter = to < ascending.size();
        return new MessageWindow(window, hasBefore, hasAfter, true);
    }

    private List<MessageRow> conversationRowsAscending(String conversationId) {
        List<MessageRow> rows = new ArrayList<>();
        for (MessageRow row : messages.values()) {
            if (row.conversationId().equals(conversationId)) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingLong(MessageRow::sequenceNo));
        return rows;
    }

    private final java.util.Map<String, AnnotationRow> annotations = new java.util.HashMap<>();
    private final java.util.Map<String, QuoteRow> quotes = new java.util.HashMap<>();
    private final java.util.Map<String, LineageRow> lineages = new java.util.HashMap<>();

    @Override
    public void upsertAnnotation(AnnotationUpsert command) {
        if (!messages.containsKey(command.messageId())) {
            throw new IllegalArgumentException("message 不存在: " + command.messageId());
        }
        annotations.put(command.messageId() + ":" + command.ownerUserId(),
                new AnnotationRow(command.messageId(), command.ownerUserId(),
                        command.favorite(), command.userNote(),
                        System.currentTimeMillis()));
    }

    @Override
    public AnnotationRow findAnnotation(String messageId, String ownerUserId) {
        return annotations.get(messageId + ":" + ownerUserId);
    }

    @Override
    public void recordQuote(QuoteRecord command) {
        if (!messages.containsKey(command.quotedMessageId())) {
            throw new IllegalArgumentException("被引消息不存在: " + command.quotedMessageId());
        }
        quotes.put(command.messageId(),
                new QuoteRow(command.messageId(), command.quotedMessageId(),
                        command.snapshot()));
    }

    @Override
    public QuoteRow findQuoteByQuotingMessage(String messageId) {
        return quotes.get(messageId);
    }

    @Override
    public void recordLineage(LineageRecord command) {
        if (!conversations.containsKey(command.parentConversationId())) {
            throw new IllegalArgumentException(
                    "父会话不存在: " + command.parentConversationId());
        }
        conversations.put(command.childConversationId(), new ConversationRow(
                command.childConversationId(), command.createdByUser(), "DRIVER", null,
                false, System.currentTimeMillis(), System.currentTimeMillis(),
                com.matrix.agent.api.conversation.ConversationInfo.TITLE_ORIGIN_DEFAULT,
                false, com.matrix.agent.api.conversation.ConversationMessage.CHANNEL_NONE));
        lineages.put(command.childConversationId(),
                new LineageRow(command.childConversationId(), command.parentConversationId(),
                        command.forkSequenceNo(), command.parentTitleAtFork(),
                        command.seedSnapshotJson(), 1));
    }

    @Override
    public LineageRow findLineage(String childConversationId) {
        return lineages.get(childConversationId);
    }

    @Override
    public String findRunningUserMessageId(String conversationId) {
        for (LinkRow link : links.values()) {
            if (link.conversationId.equals(conversationId) && link.terminalStatus == null) {
                return link.userMessageId;
            }
        }
        return null;
    }

    @Override
    public int clearForUsers(List<String> userIds) {
        clearCalls++;
        int removed = 0;
        for (String userId : userIds) {
            for (ConversationRow row : new ArrayList<>(conversations.values())) {
                if (row.ownerUserId().equals(userId)) {
                    conversations.remove(row.conversationId());
                    removed++;
                }
            }
        }
        messages.values().removeIf(row -> !conversations.containsKey(row.conversationId()));
        links.values().removeIf(link -> !conversations.containsKey(link.conversationId));
        return removed;
    }

    private static final int ROLE_USER = 0;
    private static final int ROLE_ASSISTANT = 1;
    private static final int ROLE_SYSTEM = 2;

    private static MessageRow withStatus(MessageRow row, int status, int failureCode) {
        return new MessageRow(row.messageId(), row.conversationId(), row.sequenceNo(),
                row.roleWire(), status, row.channelWire(), row.text(), row.languageTag(),
                row.conversationTaskId(), failureCode, row.createdAtMs(),
                System.currentTimeMillis(), row.inputKindWire(), row.steerHostUserMessageId(),
                row.steerDeliveryWire());
    }

    private List<MessageRow> ascending(String conversationId, int limit) {
        List<MessageRow> rows = new ArrayList<>();
        for (MessageRow row : messages.values()) {
            if (row.conversationId().equals(conversationId)) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingLong(MessageRow::sequenceNo));
        return rows.size() > limit ? new ArrayList<>(rows.subList(rows.size() - limit,
                rows.size())) : rows;
    }
}
