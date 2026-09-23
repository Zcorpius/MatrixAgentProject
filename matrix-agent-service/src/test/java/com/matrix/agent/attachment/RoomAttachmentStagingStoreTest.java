package com.matrix.agent.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationAttachment;
import com.matrix.agent.data.conversation.ConversationAttachmentDao;
import com.matrix.agent.data.conversation.ConversationAttachmentEntity;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 附件 staging 的 Room 投影（I6 §9.2）：幂等重放、READY/FAILED 落行、
 * 提交冻结、草稿删除、GC、clearForUsers。
 */
public final class RoomAttachmentStagingStoreTest {

    private static final class FakeDao implements ConversationAttachmentDao {
        final Map<String, ConversationAttachmentEntity> rows = new HashMap<>();

        @Override public ConversationAttachmentEntity getById(String attachmentId) {
            return rows.get(attachmentId);
        }

        @Override public ConversationAttachmentEntity getByOperation(String ownerUserId,
                String vehicleZone, String clientOperationId) {
            for (ConversationAttachmentEntity entity : rows.values()) {
                if (entity.ownerUserId.equals(ownerUserId)
                        && entity.vehicleZone.equals(vehicleZone)
                        && entity.clientOperationId.equals(clientOperationId)) {
                    return entity;
                }
            }
            return null;
        }

        @Override public void upsert(ConversationAttachmentEntity entity) {
            rows.put(entity.attachmentId, entity);
        }

        @Override public List<ConversationAttachmentEntity> listDraft(String conversationId) {
            List<ConversationAttachmentEntity> result = new ArrayList<>();
            for (ConversationAttachmentEntity entity : rows.values()) {
                if (entity.conversationId.equals(conversationId)
                        && entity.linkedMessageId == null) {
                    result.add(entity);
                }
            }
            result.sort((a, b) -> Long.compare(a.createdAtMs, b.createdAtMs));
            return result;
        }

        @Override public List<ConversationAttachmentEntity> listByMessage(String messageId) {
            List<ConversationAttachmentEntity> result = new ArrayList<>();
            for (ConversationAttachmentEntity entity : rows.values()) {
                if (messageId.equals(entity.linkedMessageId)) result.add(entity);
            }
            result.sort((a, b) -> Integer.compare(a.ordinal, b.ordinal));
            return result;
        }

        @Override public int linkToMessage(String attachmentId, String messageId, int ordinal) {
            ConversationAttachmentEntity entity = rows.get(attachmentId);
            if (entity == null || entity.linkedMessageId != null) return 0;
            entity.linkedMessageId = messageId;
            entity.ordinal = ordinal;
            return 1;
        }

        @Override public int deleteDraft(String ownerUserId, String vehicleZone,
                String attachmentId) {
            ConversationAttachmentEntity entity = rows.get(attachmentId);
            if (entity == null || entity.linkedMessageId != null
                    || !ownerUserId.equals(entity.ownerUserId)
                    || !vehicleZone.equals(entity.vehicleZone)) return 0;
            rows.remove(attachmentId);
            return 1;
        }

        @Override public void deleteByUsers(List<String> userIds) {
            rows.values().removeIf(entity -> userIds.contains(entity.ownerUserId));
        }

        @Override public List<ConversationAttachmentEntity> listExpiredDrafts(
                long expireBeforeMs) {
            List<ConversationAttachmentEntity> result = new ArrayList<>();
            for (ConversationAttachmentEntity entity : rows.values()) {
                if (entity.linkedMessageId == null && entity.createdAtMs < expireBeforeMs) {
                    result.add(entity);
                }
            }
            return result;
        }

        @Override public void deleteById(String attachmentId) {
            rows.remove(attachmentId);
        }
    }

    private final FakeDao dao = new FakeDao();
    private final RoomAttachmentStagingStore store =
            new RoomAttachmentStagingStore(dao, Runnable::run);

    private static InputStream text(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream binary(int size) {
        return new java.io.ByteArrayInputStream(new byte[size]);
    }

    @Test
    public void stageTextFileReady() {
        ConversationAttachmentEntity entity = store.stage("demo-driver", "DRIVER", "c1",
                text("Hello World"), "text/plain", "notes.txt", "op-1");
        assertEquals(ConversationAttachmentEntity.STATE_READY, entity.state);
        assertEquals("Hello World", entity.extractedText);
        assertEquals("notes.txt", entity.safeDisplayName);
        assertNull("草稿态未链接", entity.linkedMessageId);
    }

    @Test
    public void beginStageReturnsImmediatelyThenCompletesOnWorker() {
        RoomAttachmentStagingStore.StageStart started = store.beginStage(
                "demo-driver", "DRIVER", "c1", "text/plain", "notes.txt", "op-async");

        assertTrue("首次 stage 创建唯一的 STAGING 行", started.created());
        assertEquals(ConversationAttachment.STATE_STAGING, started.attachment().state);
        assertNull("Binder 返回前不应有正文", started.attachment().extractedText);

        RoomAttachmentStagingStore.StageStart replay = store.beginStage(
                "demo-driver", "DRIVER", "c1", "text/plain", "notes.txt", "op-async");
        assertFalse("解析尚未结束时的重放也不能启动第二次读取", replay.created());
        assertEquals(started.attachment().attachmentId, replay.attachment().attachmentId);

        store.completeStage(started.attachment().attachmentId, text("async body"), "text/plain");
        ConversationAttachmentEntity completed = dao.getById(started.attachment().attachmentId);
        assertEquals(ConversationAttachment.STATE_READY, completed.state);
        assertEquals("async body", completed.extractedText);
        assertEquals("真实字节数而不是调用方声明值", 10L, completed.byteSize);
    }

    @Test
    public void stageIdempotentReplayByOperationId() {
        ConversationAttachmentEntity first = store.stage("demo-driver", "DRIVER", "c1",
                text("Same"), "text/plain", "a.txt", "op-1");
        ConversationAttachmentEntity replay = store.stage("demo-driver", "DRIVER", "c1",
                text("Different"), "text/plain", "a.txt", "op-1");
        assertEquals("同 opId 重放返回既有行", first.attachmentId, replay.attachmentId);
        assertEquals("重放不覆盖内容", "Same", replay.extractedText);
        assertEquals(1, dao.rows.size());
    }

    @Test
    public void stageBinaryFailsWithUnsupportedMedia() {
        ConversationAttachmentEntity entity = store.stage("demo-driver", "DRIVER", "c1",
                binary(8192), "text/plain", "blob.bin", "op-2");
        assertEquals(ConversationAttachmentEntity.STATE_FAILED, entity.state);
        assertEquals(ConversationAttachment.ERROR_UNSUPPORTED_MEDIA, entity.errorCode);
        assertNull("FAILED 无正文", entity.extractedText);
    }

    @Test
    public void linkToMessageFreezesAndPreventsDraftDelete() {
        ConversationAttachmentEntity entity = store.stage("demo-driver", "DRIVER", "c1",
                text("data"), "text/plain", "a.txt", "op-1");
        dao.linkToMessage(entity.attachmentId, "msg-1", 0);
        assertEquals("msg-1", entity.linkedMessageId);
        assertFalse("已冻结不可删", store.deleteDraft("demo-driver", "DRIVER", entity.attachmentId));
        assertEquals(1, dao.listByMessage("msg-1").size());
    }

    @Test
    public void draftDeleteRemovesUnlinked() {
        ConversationAttachmentEntity entity = store.stage("demo-driver", "DRIVER", "c1",
                text("temp"), "text/plain", "a.txt", "op-1");
        assertTrue(store.deleteDraft("demo-driver", "DRIVER", entity.attachmentId));
        assertEquals(0, dao.rows.size());
    }

    @Test
    public void draftDeleteCannotCrossOwnerOrZone() {
        ConversationAttachmentEntity entity = store.stage("driver-a", "DRIVER", "c1",
                text("private"), "text/plain", "a.txt", "same-operation");

        assertFalse(store.deleteDraft("driver-b", "DRIVER", entity.attachmentId));
        assertFalse(store.deleteDraft("driver-a", "PASSENGER", entity.attachmentId));
        assertNotNull("错误 scope 不得影响附件", dao.getById(entity.attachmentId));
        assertTrue(store.deleteDraft("driver-a", "DRIVER", entity.attachmentId));
    }

    @Test
    public void gcRemovesExpiredDraftsButNotRecentOrLinked() {
        ConversationAttachmentEntity old = store.stage("demo-driver", "DRIVER", "c1",
                text("old"), "text/plain", "old.txt", "op-1");
        old.createdAtMs = System.currentTimeMillis()
                - RoomAttachmentStagingStore.DRAFT_RETENTION_MS - 1000;

        ConversationAttachmentEntity fresh = store.stage("demo-driver", "DRIVER", "c1",
                text("new"), "text/plain", "new.txt", "op-2");

        ConversationAttachmentEntity linked = store.stage("demo-driver", "DRIVER", "c1",
                text("linked"), "text/plain", "linked.txt", "op-3");
        linked.createdAtMs = old.createdAtMs;
        dao.linkToMessage(linked.attachmentId, "msg-1", 0);

        int removed = store.cleanupExpiredDrafts(System.currentTimeMillis());

        assertEquals("只删超期且未链接", 1, removed);
        assertNull(dao.getById(old.attachmentId));
        assertNotNull(dao.getById(fresh.attachmentId));
        assertNotNull(dao.getById(linked.attachmentId));
    }

    @Test
    public void clearForUsersRemovesAllByOwner() {
        store.stage("demo-driver", "DRIVER", "c1",
                text("a"), "text/plain", "a.txt", "op-1");
        store.stage("demo-driver", "DRIVER", "c2",
                text("b"), "text/plain", "b.txt", "op-2");
        store.clearForUsers(List.of("demo-driver"));
        assertEquals(0, dao.rows.size());
    }

    @Test
    public void displayNameSanitizedNoPathNoTooLong() {
        ConversationAttachmentEntity entity = store.stage("demo-driver", "DRIVER", "c1",
                text("x"), "text/plain", "/very/long/path/to/some/file.txt", "op-1");
        assertEquals("file.txt", entity.safeDisplayName);

        StringBuilder longName = new StringBuilder("n");
        for (int i = 0; i < 200; i++) longName.append('n');
        ConversationAttachmentEntity longEntity = store.stage("demo-driver", "DRIVER", "c1",
                text("x"), "text/plain", longName.toString(), "op-2");
        assertTrue("超长名截断带省略号",
                longEntity.safeDisplayName.length() <= 70);
        assertTrue(longEntity.safeDisplayName.endsWith("…"));
    }

    @Test
    public void dtoProjectionCarriesChipMetadataOnly() {
        ConversationAttachmentEntity entity = store.stage("demo-driver", "DRIVER", "c1",
                text("secret content"), "text/plain", "a.txt", "op-1");
        ConversationAttachment dto = RoomAttachmentStagingStore.toDto(entity);
        assertEquals(entity.attachmentId, dto.attachmentId);
        assertEquals(ConversationAttachment.STATE_READY, dto.state);
        assertEquals("a.txt", dto.safeDisplayName);
        assertEquals("正文不跨 Binder——DTO 只有 extractedChars",
                entity.extractedChars, dto.extractedChars);
    }
}
