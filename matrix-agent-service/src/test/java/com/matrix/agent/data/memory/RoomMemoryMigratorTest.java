package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

/**
 * RoomMemoryMigrator JVM 测试——SP 历史 preference → Room 迁移契约。
 *
 * <p>用 fake in-memory SpSource + fake MemoryRecordDao 验证:
 * <ul>
 *   <li>userId.key 格式的 SP 条目切分正确;</li>
 *   <li>EPOCH_KEY 单独走 epoch 行;</li>
 *   <li>SP 在 Room 写入后清空(成功路径);</li>
 *   <li>SP 为空时幂等跳过;</li>
 *   <li>非法格式 SP 条目被跳过不污染 Room。</li>
 * </ul>
 */
public final class RoomMemoryMigratorTest {

    @Test
    public void migrateLegacyPreferences() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.user.preference.temperature", "24");
        spData.put("demo-driver.user.preference.seat", "sport");
        spData.put("demo-passenger.user.preference.volume", "high");
        FakeSpSource sp = new FakeSpSource(spData);
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();

        new RoomMemoryMigrator(sp, dao, Runnable::run).migrate();

        assertEquals("3 个偏好和迁移标记", 4, dao.store.size());
        MemoryRecordEntity driverTemp = dao.queryByKey(
                "demo-driver", "driver", "preference", "user.preference.temperature");
        assertEquals("24", driverTemp.value);
        MemoryRecordEntity passengerVol = dao.queryByKey(
                "demo-passenger", "passenger", "preference", "user.preference.volume");
        assertEquals("high", passengerVol.value);
        assertTrue("SP 必须在成功后清空", sp.cleared);
    }

    @Test
    public void migrateEpochValue() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.k", "v");
        spData.put("__epoch__", 7L);
        FakeSpSource sp = new FakeSpSource(spData);
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();

        new RoomMemoryMigrator(sp, dao, Runnable::run).migrate();

        MemoryRecordEntity epochRow = dao.queryByKey(
                RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        assertEquals("SP epoch=7L 必须迁到 Room __epoch__ 行", "7", epochRow.value);

        // RoomMemoryStore 用同一 DAO 加载,必须读到 epoch=7L
        RoomMemoryStore storeFromMigrated = new RoomMemoryStore(dao);
        assertEquals("RoomMemoryStore 加载迁移后 epoch=7L", 7L, storeFromMigrated.currentEpoch());
    }

    @Test
    public void migratorIsIdempotentWhenSpEmpty() {
        FakeSpSource sp = new FakeSpSource(new LinkedHashMap<>());  // 空 SP
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        sp.clearReturned = false;  // 第二次跑应该 skip 不调 clear

        new RoomMemoryMigrator(sp, dao, Runnable::run).migrate();

        assertEquals("空 SP 仍写入一次性标记", 1, dao.store.size());
        assertFalse("空 SP 时不应调 clear", sp.clearCalled);
    }

    @Test
    public void migratorSkipsMalformedSpKeys() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("no-dot-key", "value");              // 无 dot——非法格式
        spData.put(".starts-with-dot", "value");        // userId 空
        spData.put("ends-with-dot.", "value");          // key 空
        spData.put("demo-driver.valid-key", "good");
        spData.put("__epoch__", 0L);                    // epoch=0 跳过(< 1L)
        FakeSpSource sp = new FakeSpSource(spData);
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();

        new RoomMemoryMigrator(sp, dao, Runnable::run).migrate();

        assertEquals("1 条偏好及迁移标记", 2, dao.store.size());
        assertNull(dao.queryByKey("no-dot-key", "global", "preference", ""));
        assertEquals("good", dao.queryByKey(
                "demo-driver", "driver", "preference", "valid-key").value);
    }

    @Test
    public void migratorHandlesIntegerEpoch() {
        // SharedPreferences.getLong 在某些 ROM 上会把 Long 存成 Integer(进度条/反序列化)
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("__epoch__", 5);  // Integer 而非 Long
        FakeSpSource sp = new FakeSpSource(spData);
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();

        new RoomMemoryMigrator(sp, dao, Runnable::run).migrate();

        MemoryRecordEntity epochRow = dao.queryByKey(
                RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        assertEquals("Integer epoch=5 也必须迁移", "5", epochRow.value);
    }

    @Test
    public void failedSourceClearCannotReplayOrOverwriteNewerValue() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demo-driver.preferred_temperature", "24");
        FakeSpSource sp = new FakeSpSource(data);
        sp.clearReturned = false;
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        assertFalse(new RoomMemoryMigrator(sp, dao, Runnable::run).migrate());
        assertEquals(1, sp.data.size());

        MemoryRecordEntity newer = dao.queryByKey("demo-driver", "driver", "preference",
                "preferred_temperature");
        newer.value = "26";
        dao.upsert(newer);
        sp.clearReturned = true;
        assertTrue(new RoomMemoryMigrator(sp, dao, Runnable::run).migrate());
        assertEquals("26", dao.queryByKey("demo-driver", "driver", "preference",
                "preferred_temperature").value);
        assertTrue(sp.data.isEmpty());
    }

    @Test
    public void existingEpochCanNeverMoveBackwardDuringMigration() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("__epoch__", 3L);
        FakeSpSource sp = new FakeSpSource(data);
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        MemoryRecordEntity epoch = new MemoryRecordEntity();
        epoch.userId = RoomMemoryStore.SYSTEM_USER;
        epoch.zone = RoomMemoryStore.SYSTEM_ZONE;
        epoch.layer = RoomMemoryStore.PREFERENCE_LAYER;
        epoch.key = RoomMemoryStore.EPOCH_KEY;
        epoch.value = "7";
        dao.upsert(epoch);
        assertTrue(new RoomMemoryMigrator(sp, dao, Runnable::run).migrate());
        assertEquals("7", dao.queryByKey(epoch.userId, epoch.zone, epoch.layer, epoch.key).value);
    }

    @Test
    public void resetMarkerPreventsImportingOldSource() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demo-driver.preferred_temperature", "24");
        FakeSpSource sp = new FakeSpSource(data);
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        dao.upsert(RoomMemoryMigrator.markerEntity());
        assertTrue(new RoomMemoryMigrator(sp, dao, Runnable::run).migrate());
        assertNull(dao.queryByKey("demo-driver", "driver", "preference",
                "preferred_temperature"));
    }

    /** Fake SpSource:in-memory Map + clear 调用追踪。 */
    private static final class FakeSpSource implements RoomMemoryMigrator.SpSource {
        final Map<String, ?> data;
        boolean cleared = false;
        boolean clearCalled = false;
        boolean clearReturned = true;

        FakeSpSource(Map<String, ?> data) {
            this.data = new LinkedHashMap<>(data);
        }

        @Override
        public Map<String, ?> getAll() {
            return new LinkedHashMap<>(data);
        }

        @Override
        public boolean clear() {
            clearCalled = true;
            if (clearReturned) data.clear();
            cleared = clearReturned;
            return clearReturned;
        }
    }

    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) { return 0; }

        final Map<String, MemoryRecordEntity> store = new LinkedHashMap<>();

        private static String key(MemoryRecordEntity e) {
            return e.userId + "@" + e.zone + "#" + e.layer + "/" + e.key;
        }

        @Override
        public void upsert(MemoryRecordEntity entity) {
            store.put(key(entity), entity);
        }

        @Override
        public List<MemoryRecordEntity> queryByUserZoneLayer(String userId, String zone, String layer) {
            List<MemoryRecordEntity> result = new ArrayList<>();
            for (MemoryRecordEntity e : store.values()) {
                if (e.userId.equals(userId) && e.zone.equals(zone) && e.layer.equals(layer)) {
                    result.add(e);
                }
            }
            return result;
        }

        @Override
        public MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key) {
            return store.get(userId + "@" + zone + "#" + layer + "/" + key);
        }

        @Override
        public int deleteByUser(String userId) {
            int removed = 0;
            for (String k : new ArrayList<>(store.keySet())) {
                if (k.startsWith(userId + "@")) {
                    store.remove(k);
                    removed++;
                }
            }
            return removed;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (String k : new ArrayList<>(store.keySet())) {
                if (k.startsWith(userId + "@" + zone + "#")) {
                    store.remove(k);
                    removed++;
                }
            }
            return removed;
        }
    }
}
