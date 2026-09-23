package com.matrix.agent.conversation.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.conversation.ConversationDraftStore;
import com.matrix.agent.data.conversation.ConversationConsumedDraftEntity;
import com.matrix.agent.data.conversation.ConversationDraftDao;
import com.matrix.agent.data.conversation.ConversationDraftEntity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿两表投影（I4 §7.2）：revision 规则、tombstone 拒绝（草稿复活防线）、
 * discard 条件删除、清理谓词（7 天 TTL 或每会话最多 128 条）。
 */
public final class RoomConversationDraftStoreTest {

    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final class FakeDao implements ConversationDraftDao {
        final Map<String, ConversationDraftEntity> drafts = new HashMap<>();
        final Map<String, ConversationConsumedDraftEntity> consumed = new HashMap<>();

        private static String draftKey(String owner, String zone, String conversation) {
            return owner + "|" + zone + "|" + conversation;
        }

        private static String consumedKey(String owner, String zone, String conversation,
                String instance) {
            return owner + "|" + zone + "|" + conversation + "|" + instance;
        }

        @Override public ConversationDraftEntity getDraft(String ownerUserId,
                String vehicleZone, String conversationId) {
            return drafts.get(draftKey(ownerUserId, vehicleZone, conversationId));
        }

        @Override public void upsertDraft(ConversationDraftEntity entity) {
            drafts.put(draftKey(entity.ownerUserId, entity.vehicleZone, entity.conversationId),
                    entity);
        }

        @Override public void deleteDraft(String ownerUserId, String vehicleZone,
                String conversationId) {
            drafts.remove(draftKey(ownerUserId, vehicleZone, conversationId));
        }

        @Override public void deleteDraftsByUsers(List<String> userIds) {
            drafts.keySet().removeIf(key -> userIds.contains(key.split("\\|")[0]));
        }

        @Override public int countConsumed(String ownerUserId, String vehicleZone,
                String conversationId, String draftInstanceId) {
            return consumed.containsKey(consumedKey(ownerUserId, vehicleZone, conversationId,
                    draftInstanceId)) ? 1 : 0;
        }

        @Override public void insertConsumed(ConversationConsumedDraftEntity entity) {
            consumed.put(consumedKey(entity.ownerUserId, entity.vehicleZone,
                    entity.conversationId, entity.draftInstanceId), entity);
        }

        @Override public List<ConversationConsumedDraftEntity> listAllConsumed() {
            return new ArrayList<>(consumed.values());
        }

        @Override public void deleteConsumed(String ownerUserId, String vehicleZone,
                String conversationId, String draftInstanceId) {
            consumed.remove(consumedKey(ownerUserId, vehicleZone, conversationId,
                    draftInstanceId));
        }

        @Override public void deleteConsumedByUsers(List<String> userIds) {
            consumed.keySet().removeIf(key -> userIds.contains(key.split("\\|")[0]));
        }
    }

    private final FakeDao dao = new FakeDao();
    private final RoomConversationDraftStore store = new RoomConversationDraftStore(dao,
            Runnable::run);

    private ConversationDraftStore.SaveCommand command(String instance, long revision,
            String text) {
        return new ConversationDraftStore.SaveCommand("demo-driver", "DRIVER", "c1",
                instance, revision, text, 0, Math.min(text.length(), 0), 1L);
    }

    @Test
    public void sameInstanceRequiresGreaterRevision() {
        assertEquals(ConversationDraftStore.SaveResult.SAVED,
                store.save(command("i1", 1, "hello")));
        assertEquals("同 instance 更大 revision 覆盖",
                ConversationDraftStore.SaveResult.SAVED,
                store.save(command("i1", 2, "hello world")));
        assertEquals("乱序迟到的旧 revision 是幂等 no-op",
                ConversationDraftStore.SaveResult.IDEMPOTENT_STALE_REVISION,
                store.save(command("i1", 1, "hello")));
        assertEquals("hello world", store.get("demo-driver", "DRIVER", "c1").text());
    }

    @Test
    public void newInstanceReplacesCurrentDraft() {
        store.save(command("i1", 5, "old"));
        assertEquals(ConversationDraftStore.SaveResult.SAVED,
                store.save(command("i2", 1, "new")));
        assertEquals("i2", store.get("demo-driver", "DRIVER", "c1").draftInstanceId());
    }

    @Test
    public void consumedInstanceIsRejectedSoSentTextCannotResurrect() {
        store.save(command("i1", 1, "已发送的内容"));
        store.consumeCurrentInCallerTransaction("demo-driver", "DRIVER", "c1");

        assertNull("提交受理事务已删除草稿", store.get("demo-driver", "DRIVER", "c1"));
        assertEquals("迟到的旧 instance 保存必须拒绝（复活防线）",
                ConversationDraftStore.SaveResult.REJECTED_CONSUMED_INSTANCE,
                store.save(command("i1", 2, "已发送的内容")));
        assertEquals("新 instance（新编辑生命周期）仍可保存",
                ConversationDraftStore.SaveResult.SAVED,
                store.save(command("i2", 1, "重新开始")));
    }

    @Test
    public void consumingSubmittedInstanceNeverDeletesANewerDraftInstance() {
        store.save(command("submitted", 3, "将发送的内容"));
        // 用户点击发送后已经开启下一次编辑生命周期；提交只能消费 captured instance。
        store.save(command("new-editor", 1, "下一条草稿"));

        store.consumeSubmittedInCallerTransaction("demo-driver", "DRIVER", "c1",
                "submitted");

        assertEquals("新 instance 不能被旧提交误删", "new-editor",
                store.get("demo-driver", "DRIVER", "c1").draftInstanceId());
        assertEquals("已提交 instance 即使此前尚未是当前行也必须 tombstone",
                ConversationDraftStore.SaveResult.REJECTED_CONSUMED_INSTANCE,
                store.save(command("submitted", 4, "迟到保存")));
    }

    @Test
    public void payloadBoundsAreRejected() {
        assertEquals("超 12KiB 拒绝",
                ConversationDraftStore.SaveResult.REJECTED_PAYLOAD,
                store.save(command("i1", 1, "字".repeat(
                        ConversationDraftStore.MAX_TEXT_BYTES))));
        assertEquals("selection 越界拒绝",
                ConversationDraftStore.SaveResult.REJECTED_PAYLOAD,
                store.save(new ConversationDraftStore.SaveCommand("demo-driver", "DRIVER",
                        "c1", "i1", 1, "abc", 0, 99, 1L)));
    }

    @Test
    public void discardRemovesOnlyMatchingInstance() {
        store.save(command("i1", 3, "text"));
        store.discard("demo-driver", "DRIVER", "c1", "i-other");
        assertEquals("instance 不匹配不删", "i1",
                store.get("demo-driver", "DRIVER", "c1").draftInstanceId());

        store.discard("demo-driver", "DRIVER", "c1", "i1");
        assertNull(store.get("demo-driver", "DRIVER", "c1"));
        assertEquals("discard 后同 instance 保存被 tombstone 拒绝（清空不复活）",
                ConversationDraftStore.SaveResult.REJECTED_CONSUMED_INSTANCE,
                store.save(command("i1", 4, "text")));
    }

    @Test
    public void cleanupHonorsBothTimeAndCountBounds() {
        long now = 1_000_000_000L;
        // 会话 A：128 条都在时间窗口内 + 第 129 条也很新，仍必须受数量上界回收。
        for (int i = 1; i <= 128; i++) {
            insertConsumed("cA", "inst-A-" + i, now - i);
        }
        insertConsumed("cA", "inst-A-129", now - 129L);
        // 会话 B：即使数量很少，超 TTL 也必须回收，避免永久积累。
        insertConsumed("cB", "inst-B-1", now - TTL_MS - 1L);

        int removed = store.cleanupTombstones(now);

        assertEquals("数量上界与 TTL 都独立生效", 2, removed);
        assertTrue("数量窗口内的新记录保留",
                dao.consumed.containsKey(keyOf("cA", "inst-A-128")));
        assertTrue(dao.consumed.containsKey(keyOf("cA", "inst-A-1")));
        assertTrue("越界记录被删除",
                !dao.consumed.containsKey(keyOf("cA", "inst-A-129")));
        assertTrue("超时记录被删除", !dao.consumed.containsKey(keyOf("cB", "inst-B-1")));
    }

    @Test
    public void clearForUsersRemovesDraftsAndTombstones() {
        store.save(command("i1", 1, "text"));
        store.consumeCurrentInCallerTransaction("demo-driver", "DRIVER", "c1");
        assertTrue(dao.consumed.size() > 0);

        store.clearForUsers(List.of("demo-driver"));

        assertEquals(0, dao.drafts.size());
        assertEquals(0, dao.consumed.size());
    }

    private void insertConsumed(String conversationId, String instance, long consumedAtMs) {
        ConversationConsumedDraftEntity entity = new ConversationConsumedDraftEntity();
        entity.ownerUserId = "demo-driver";
        entity.vehicleZone = "DRIVER";
        entity.conversationId = conversationId;
        entity.draftInstanceId = instance;
        entity.consumedAtMs = consumedAtMs;
        dao.insertConsumed(entity);
    }

    private String keyOf(String conversationId, String instance) {
        return "demo-driver|DRIVER|" + conversationId + "|" + instance;
    }
}
