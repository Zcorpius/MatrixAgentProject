package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;

import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 自动标题（评估 v1.0 §4.1）：触发状态过滤、比较交换、净化与静默降级。 */
public final class ConversationTitleServiceTest {

    private static final class FakeClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile String reply = "空调调温";
        volatile boolean fail;

        @Override public String complete(ModelConfig config, String systemPrompt,
                String userPrompt) {
            throw new UnsupportedOperationException();
        }

        @Override public String complete(ModelConfig config, String systemPrompt,
                String userPrompt, com.matrix.agent.identity.CancellationToken token,
                long deadlineAtMillis) throws Exception {
            calls.incrementAndGet();
            if (fail) {
                throw new java.io.IOException("offline");
            }
            return reply;
        }
    }

    private static ConversationTitleService service(FakeConversationStore store,
            FakeClient client) {
        ModelConfig inline = new ModelConfig("demo", "Demo", null, "http://localhost",
                "demo-model", null, false);
        Supplier<ModelConfig> config = () -> inline;
        java.util.concurrent.ExecutorService direct =
                new com.matrix.agent.conversation.ConversationCoordinatorTest.DirectPool();
        return new ConversationTitleService(store, client, config, direct);
    }

    /** COMPLETED 触发 → DEFAULT 行写入 AUTO；再次触发不再调用 LLM（至多一次生成）。 */
    @Test public void generatesAutoTitleOnceForDefaultOriginConversation() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, UUID.randomUUID().toString());
        FakeClient client = new FakeClient();
        ConversationTitleService title = service(store, client);

        String userMessageId = seedTerminalRound(store,
                PersistedMessageStatus.COMPLETED);
        title.onTerminalRound(CONV, userMessageId,
                PersistedMessageStatus.COMPLETED.wire());
        title.onTerminalRound(CONV, userMessageId,
                PersistedMessageStatus.COMPLETED.wire());

        assertEquals("LLM 至多调用一次（第二次因 AUTO 已写入被预检跳过）", 1,
                client.calls.get());
        ConversationStore.ConversationRow row = store.findConversation(CONV);
        assertEquals(ConversationInfo.TITLE_ORIGIN_AUTO, row.titleOrigin());
        assertEquals("空调调温", row.title());
    }

    /** REJECTED / CANCELLED 不触发（标题描述主题而非执行成败）。 */
    @Test public void rejectedAndCancelledRoundsDoNotTrigger() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, UUID.randomUUID().toString());
        FakeClient client = new FakeClient();
        ConversationTitleService title = service(store, client);

        title.onTerminalRound(CONV, "msg-x", PersistedMessageStatus.REJECTED.wire());
        title.onTerminalRound(CONV, "msg-x", PersistedMessageStatus.CANCELLED.wire());

        assertEquals(0, client.calls.get());
        assertEquals(ConversationInfo.TITLE_ORIGIN_DEFAULT,
                store.findConversation(CONV).titleOrigin());
    }

    /** 用户命名（USER）与既有 AUTO 永不被覆盖——比较交换语义。 */
    @Test public void userOriginAndExistingAutoAreNeverOverridden() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, UUID.randomUUID().toString());
        FakeClient client = new FakeClient();
        ConversationTitleService title = service(store, client);

        // 用户先命名
        store.renameConversation(CONV, "我的对话");
        String userMessageId = seedTerminalRound(store,
                PersistedMessageStatus.COMPLETED);
        title.onTerminalRound(CONV, userMessageId,
                PersistedMessageStatus.COMPLETED.wire());

        assertEquals("预检即跳过，不烧 LLM 调用", 0, client.calls.get());
        assertEquals("我的对话", store.findConversation(CONV).title());
        assertEquals(ConversationInfo.TITLE_ORIGIN_USER,
                store.findConversation(CONV).titleOrigin());
    }

    /** 离线/失败静默保留默认标题，不抛出、不影响任何行。 */
    @Test public void providerFailureSilentlyKeepsDefaultTitle() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, UUID.randomUUID().toString());
        FakeClient client = new FakeClient();
        client.fail = true;
        ConversationTitleService title = service(store, client);

        String userMessageId = seedTerminalRound(store,
                PersistedMessageStatus.FAILED);
        title.onTerminalRound(CONV, userMessageId, PersistedMessageStatus.FAILED.wire());

        assertEquals(1, client.calls.get());
        assertEquals("失败后仍是 DEFAULT，可由后续轮次重试",
                ConversationInfo.TITLE_ORIGIN_DEFAULT,
                store.findConversation(CONV).titleOrigin());
    }

    /** 标题净化：单行化、去引号装饰、截断到 TITLE_MAX_CHARS。 */
    @Test public void sanitizeProducesSingleLineTrimmedTitle() {
        assertEquals("空调调温", ConversationTitleService.sanitizeTitle("「空调调温」"));
        assertEquals("空调调温", ConversationTitleService.sanitizeTitle("空调调温\n"));
        String overlong = "长".repeat(45);
        assertEquals("超长截断到自动标题 10 字上限",
                "长".repeat(ConversationTitleService.AUTO_TITLE_MAX_CODE_POINTS),
                ConversationTitleService.sanitizeTitle(overlong));
        assertEquals("换行单行化", "空调 调温",
                ConversationTitleService.sanitizeTitle("空调" + (char) 10 + "调温"));
        assertEquals("", ConversationTitleService.sanitizeTitle("   "));
        assertEquals("", ConversationTitleService.sanitizeTitle(null));
    }

    @Test public void notifiesPresentationOnlyAfterSuccessfulTitleWrite() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, UUID.randomUUID().toString());
        FakeClient client = new FakeClient();
        ConversationTitleService title = service(store, client);
        java.util.concurrent.atomic.AtomicReference<ConversationStore.ConversationRow> notified =
                new java.util.concurrent.atomic.AtomicReference<>();
        title.setTitleChangedSink(notified::set);

        String userMessageId = seedTerminalRound(store, PersistedMessageStatus.COMPLETED);
        title.onTerminalRound(CONV, userMessageId, PersistedMessageStatus.COMPLETED.wire());

        assertEquals("空调调温", notified.get().title());
        assertEquals(ConversationInfo.TITLE_ORIGIN_AUTO, notified.get().titleOrigin());
    }

    /** EXECUTION_UNKNOWN 同样触发（写操作的未知终态仍是该轮的终态）。 */
    @Test public void executionUnknownTriggersTitleGeneration() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, UUID.randomUUID().toString());
        FakeClient client = new FakeClient();
        ConversationTitleService title = service(store, client);

        String userMessageId = seedTerminalRound(store,
                PersistedMessageStatus.EXECUTION_UNKNOWN);
        title.onTerminalRound(CONV, userMessageId,
                PersistedMessageStatus.EXECUTION_UNKNOWN.wire());

        assertEquals(1, client.calls.get());
        assertEquals(ConversationInfo.TITLE_ORIGIN_AUTO,
                store.findConversation(CONV).titleOrigin());
    }

    private static final String CONV = UUID.randomUUID().toString();

    /** 落一条已终态的用户消息（store 级直插，隔离执行器线程模型）。 */
    private static String seedTerminalRound(FakeConversationStore store,
            PersistedMessageStatus status) {
        String messageId = ConversationIds.newMessageId();
        store.submitUserMessage(new ConversationStore.UserSubmission(
                CONV, messageId, com.matrix.agent.api.conversation.ConversationMessage.CHANNEL_TEXT,
                "帮我把空调温度调低一点", "zh-CN", "task-" + messageId,
                "req-" + messageId, true, "key-" + messageId));
        store.markRunning("task-" + messageId);
        store.writeTerminal(new ConversationStore.TerminalWrite(
                "task-" + messageId, status.wire(), 0, null, null));
        return messageId;
    }
}
