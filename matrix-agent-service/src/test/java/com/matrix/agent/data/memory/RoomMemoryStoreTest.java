package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.identity.VehicleZone;

/**
 * RoomMemoryStore 主路径 JVM 契约测试。
 *
 * <p>用 fake in-memory DAO 验证:
 * <ul>
 *   <li>putPreference / getPreference / getAllPreferences / clear 走 Room memory_record 表;</li>
 *   <li>epoch 持久化到特殊行,cross-instance 加载不丢;</li>
 *   <li>putPreferenceChecked epoch 校验 + check-then-act race 保护;</li>
 *   <li>clearUserDataAndBump 通过 TransactionRunner 原子执行 bumpEpoch + delete×2。</li>
 * </ul>
 *
 * <p>真实 Room _Impl.java SQL 验证由 androidTest RoomMemoryStoreInstrumentedTest 负责。
 */
public final class RoomMemoryStoreTest {

    @Test
    public void putAndGetPreferenceRoundTrip() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.putPreference("demo-driver", "user.preference.temperature", "24");

        assertEquals("24", store.getPreference("demo-driver", "user.preference.temperature"));
        assertNull("miss returns null", store.getPreference("demo-driver", "missing"));
    }

    @Test
    public void getPreferenceDoesNotLeakAcrossUsers() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.putPreference("demo-driver", "key", "driver-value");
        store.putPreference("demo-passenger", "key", "passenger-value");

        assertEquals("driver-value", store.getPreference("demo-driver", "key"));
        assertEquals("passenger-value", store.getPreference("demo-passenger", "key"));
    }

    @Test
    public void scopedPreferencesDoNotLeakAcrossZonesForSameUser() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);
        MemoryScope driver = new MemoryScope("shared-account", VehicleZone.DRIVER);
        MemoryScope passenger = new MemoryScope("shared-account", VehicleZone.PASSENGER);

        store.putPreference(driver, "temperature", "23");
        store.putPreference(passenger, "temperature", "26");

        assertEquals("23", store.getPreference(driver, "temperature"));
        assertEquals("26", store.getPreference(passenger, "temperature"));
        assertTrue("legacy GLOBAL scope must not silently expose zone-scoped data",
                store.getAllPreferences("shared-account").isEmpty());
    }

    @Test
    public void getAllPreferencesExcludesEpochRow() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.putPreference("demo-driver", "key-a", "value-a");
        store.bumpEpoch();  // 写入 epoch 行到 (demo-driver, "global", "preference", "__epoch__")?
                           // 不——epoch 行在 __system__ 用户下,不会污染 demo-driver getAll。
        store.putPreference("demo-driver", "key-b", "value-b");

        Map<String, String> all = store.getAllPreferences("demo-driver");
        assertEquals(2, all.size());
        assertEquals("value-a", all.get("key-a"));
        assertEquals("value-b", all.get("key-b"));
        assertFalse("epoch 行绝不能暴露给 caller", all.containsKey("__epoch__"));
    }

    @Test
    public void clearRemovesUserPreferences() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.putPreference("demo-driver", "k1", "v1");
        store.putPreference("demo-passenger", "k2", "v2");

        store.clear("demo-driver");

        assertNull(store.getPreference("demo-driver", "k1"));
        assertEquals("demo-passenger 偏好保留", "v2",
                store.getPreference("demo-passenger", "k2"));
    }

    @Test
    public void epochDefaultsToZeroWhenNoRow() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);
        assertEquals(0L, store.currentEpoch());
    }

    @Test
    public void bumpEpochPersistsAndCrossInstanceLoads() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();

        RoomMemoryStore first = new RoomMemoryStore(dao);
        first.bumpEpoch();
        first.bumpEpoch();
        first.bumpEpoch();

        assertEquals(3L, first.currentEpoch());

        // 同一 DAO(fake store 共享),新建 RoomMemoryStore——必须从 epoch 行加载到 3L
        RoomMemoryStore reloaded = new RoomMemoryStore(dao);
        assertEquals("epoch 必须跨实例加载(模拟进程重启)", 3L, reloaded.currentEpoch());
    }

    @Test
    public void putPreferenceCheckedRejectsStaleEpoch() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.bumpEpoch();  // current=1L
        boolean accepted = store.putPreferenceChecked("demo-driver", "k", "v", 0L /* stale */);

        assertFalse("stale epoch 必须拒绝", accepted);
        assertNull("stale 写入不落盘", store.getPreference("demo-driver", "k"));
    }

    @Test
    public void putPreferenceCheckedAcceptsCurrentEpoch() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.bumpEpoch();  // current=1L
        boolean accepted = store.putPreferenceChecked("demo-driver", "k", "v", 1L);

        assertTrue("current epoch 必须接受", accepted);
        assertEquals("v", store.getPreference("demo-driver", "k"));
    }

    @Test
    public void clearUserDataAndBumpRunsAllThreeStepsAtomically() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        AtomicInteger txInvocations = new AtomicInteger(0);
        // 简单 transaction runner——记录调用次数,body 直接 run
        RoomMemoryStore.TransactionRunner tx = body -> {
            txInvocations.incrementAndGet();
            body.run();
        };
        RoomMemoryStore store = new RoomMemoryStore(dao, tx);

        store.putPreference("demo-driver", "k1", "v1");
        store.putPreference("demo-passenger", "k2", "v2");
        assertEquals(0L, store.currentEpoch());

        long newEpoch = store.clearUserDataAndBump("demo-driver", "demo-passenger");

        assertEquals("epoch 推进到 1L", 1L, newEpoch);
        assertEquals(1L, store.currentEpoch());
        assertNull("demo-driver 偏好清空", store.getPreference("demo-driver", "k1"));
        assertNull("demo-passenger 偏好清空", store.getPreference("demo-passenger", "k2"));
        assertEquals("transaction runner 被调用一次", 1, txInvocations.get());
    }

    @Test
    public void clearUserDataAndBumpRollsBackOnTransactionFailure() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        // 注入失败 transaction runner——模拟 Room transaction 异常
        RoomMemoryStore.TransactionRunner failingTx = body -> {
            throw new RuntimeException("simulated SQLCipher failure");
        };
        RoomMemoryStore store = new RoomMemoryStore(dao, failingTx);

        store.putPreference("demo-driver", "k1", "v1");
        assertEquals(0L, store.currentEpoch());

        boolean caught = false;
        try {
            store.clearUserDataAndBump("demo-driver", "demo-passenger");
        } catch (IllegalStateException ex) {
            caught = true;
        }
        assertTrue("transaction 失败必须抛 IllegalStateException", caught);

        assertEquals("失败后 epoch 必须回滚到原值", 0L, store.currentEpoch());
        assertEquals("失败后用户偏好必须保留(整个 transaction 回滚)",
                "v1", store.getPreference("demo-driver", "k1"));
    }

    @Test
    public void bumpEpochPersistsToSystemRowNotUserRow() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao);

        store.putPreference("demo-driver", "key", "value");
        store.bumpEpoch();

        // epoch 行必须写入 __system__ 用户,不能污染真实用户的 getAllPreferences
        Map<String, String> driverAll = store.getAllPreferences("demo-driver");
        assertEquals(1, driverAll.size());
        assertEquals("value", driverAll.get("key"));

        // 直接验证 DAO 内部 epoch 行存在
        MemoryRecordEntity epochRow = dao.queryByKey(
                RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        assertEquals("1", epochRow.value);
    }

    /** Fake in-memory DAO——语义对齐真实 Room _Impl.java。 */
    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
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
