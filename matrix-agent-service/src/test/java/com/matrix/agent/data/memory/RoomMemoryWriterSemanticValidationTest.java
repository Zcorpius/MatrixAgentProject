package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

/**
 * RoomMemoryWriter.writeSemantic writer-side defence-in-depth 测试。
 *
 * <p>Schema 在 CapabilityRegistry.memory.semantic.save 注册时已强制 pattern/maxLength,
 * 但 Writer 入口仍 fail-closed 校验——防 Provider 漏检(未来真实 AAOS Provider / 第三方实现)
 * 或测试桩绕过 PolicyEngine schema 直接调 writer。
 *
 * <p>用户硬约束:"在 Schema / Policy / Writer 至少一层强制"——Schema + Writer 双层。
 *
 * <p>覆盖 5 cases:
 * <ul>
 *   <li>namespace 外 key → false + upsert 0 次</li>
 *   <li>超长 value → false</li>
 *   <li>score 非法(NaN / > 1 / < 0)→ false</li>
 *   <li>超长 sourceSessionId → false</li>
 *   <li>null value → false</li>
 * </ul>
 *
 * <p>所有 case 都用合法 requestEpoch=0 + seedEpoch=0(epoch check 通过),所以拒绝完全由
 * writer validation 触发——证明 writer-side 是独立 gate。
 */
public final class RoomMemoryWriterSemanticValidationTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    private static void seedEpoch(FakeMemoryDao dao, long epoch) {
        MemoryRecordEntity row = new MemoryRecordEntity();
        row.userId = RoomMemoryStore.SYSTEM_USER;
        row.zone = RoomMemoryStore.SYSTEM_ZONE;
        row.layer = RoomMemoryStore.PREFERENCE_LAYER;
        row.key = RoomMemoryStore.EPOCH_KEY;
        row.value = Long.toString(epoch);
        row.capturedAtMs = System.currentTimeMillis();
        dao.store.add(row);
    }

    @Test
    public void nonNamespaceKeyRejectedByWriter() {
        FakeMemoryDao memoryDao = new FakeMemoryDao();
        seedEpoch(memoryDao, 0L);
        RoomMemoryWriter writer = new RoomMemoryWriter(null, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "random.key", "value", 1.0, "sess", 0L);

        assertFalse("namespace 外 key → writer 拒绝", accepted);
        assertEquals("upsert 必须 0 次(writer validation 在 epoch check 之后)",
                0, memoryDao.upsertCount.get());
    }

    @Test
    public void overlyLongValueRejectedByWriter() {
        FakeMemoryDao memoryDao = new FakeMemoryDao();
        seedEpoch(memoryDao, 0L);
        RoomMemoryWriter writer = new RoomMemoryWriter(null, memoryDao, null, syncRunner());

        String longValue = new String(new char[2049]).replace('\0', 'x');
        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", longValue, 1.0, "sess", 0L);

        assertFalse("超长 value → writer 拒绝", accepted);
        assertEquals(0, memoryDao.upsertCount.get());
    }

    @Test
    public void scoreOutOfRangeRejectedByWriter() {
        FakeMemoryDao memoryDao = new FakeMemoryDao();
        seedEpoch(memoryDao, 0L);
        RoomMemoryWriter writer = new RoomMemoryWriter(null, memoryDao, null, syncRunner());

        // 3 种非法 score
        assertFalse("score NaN → 拒绝",
                writer.writeSemantic("u", "DRIVER", "allergy.peanut", "v",
                        Double.NaN, "sess", 0L));
        assertFalse("score > 1 → 拒绝",
                writer.writeSemantic("u", "DRIVER", "allergy.peanut", "v",
                        1.5, "sess", 0L));
        assertFalse("score < 0 → 拒绝",
                writer.writeSemantic("u", "DRIVER", "allergy.peanut", "v",
                        -0.1, "sess", 0L));
        assertEquals("3 次非法 score 都不进 upsert",
                0, memoryDao.upsertCount.get());
    }

    @Test
    public void overlyLongSourceSessionIdRejectedByWriter() {
        FakeMemoryDao memoryDao = new FakeMemoryDao();
        seedEpoch(memoryDao, 0L);
        RoomMemoryWriter writer = new RoomMemoryWriter(null, memoryDao, null, syncRunner());

        String longSessionId = new String(new char[129]).replace('\0', 's');
        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", "v", 1.0, longSessionId, 0L);

        assertFalse("超长 sourceSessionId(> 128)→ writer 拒绝", accepted);
        assertEquals(0, memoryDao.upsertCount.get());
    }

    @Test
    public void nullValueRejectedByWriter() {
        FakeMemoryDao memoryDao = new FakeMemoryDao();
        seedEpoch(memoryDao, 0L);
        RoomMemoryWriter writer = new RoomMemoryWriter(null, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", null, 1.0, "sess", 0L);

        assertFalse("null value → writer 拒绝", accepted);
        assertEquals(0, memoryDao.upsertCount.get());
    }

    private static final class FakeMemoryDao implements MemoryRecordDao {
        final List<MemoryRecordEntity> store = new ArrayList<>();
        final AtomicInteger upsertCount = new AtomicInteger();
        @Override public void upsert(MemoryRecordEntity entity) {
            upsertCount.incrementAndGet();
            store.add(entity);
        }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            return Collections.emptyList();
        }
        @Override public MemoryRecordEntity queryByKey(String u, String z, String l, String k) {
            for (MemoryRecordEntity e : store) {
                if (u.equals(e.userId) && z.equals(e.zone)
                        && l.equals(e.layer) && k.equals(e.key)) {
                    return e;
                }
            }
            return null;
        }
        @Override public int deleteByUser(String u) { return 0; }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }
}
