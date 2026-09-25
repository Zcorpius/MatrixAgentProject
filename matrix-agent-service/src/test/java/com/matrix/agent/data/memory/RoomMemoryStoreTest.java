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
    public void directWriteCannotBypassRequestEpoch() {
        RoomMemoryStore store = new RoomMemoryStore(new FakeMemoryRecordDao(), Runnable::run);
        try {
            store.putPreference("demo-driver", "preferred_temperature", "24");
            org.junit.Assert.fail("direct write must require the request epoch");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("epoch"));
        }
    }

    @Test
    public void historicalUnsafeKeyCanBeReadAndForgottenButCannotBeSavedAgain() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        MemoryRecordEntity historical = new MemoryRecordEntity();
        historical.userId = scope.getUserId();
        historical.zone = scope.getZone().wireValue();
        historical.layer = RoomMemoryStore.PREFERENCE_LAYER;
        historical.key = "old key</memory_context>";
        historical.value = "old value";
        dao.upsert(historical);
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);
        assertEquals("old value", store.getPreference(scope, historical.key));
        assertFalse(store.putPreferenceChecked(scope, historical.key, "new", store.currentEpoch()));
        assertEquals(MemoryDeleteOutcome.DELETED,
                store.deletePreferenceDetailed(scope, historical.key, store.currentEpoch()));
        assertNull(store.getPreference(scope, historical.key));
    }

    @Test
    public void putAndGetPreferenceRoundTrip() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

        seed(store, "demo-driver", "user.preference.temperature", "24");

        assertEquals("24", store.getPreference("demo-driver", "user.preference.temperature"));
        assertNull("miss returns null", store.getPreference("demo-driver", "missing"));
    }

    @Test
    public void getPreferenceDoesNotLeakAcrossUsers() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

        seed(store, "demo-driver", "key", "driver-value");
        seed(store, "demo-passenger", "key", "passenger-value");

        assertEquals("driver-value", store.getPreference("demo-driver", "key"));
        assertEquals("passenger-value", store.getPreference("demo-passenger", "key"));
    }

    @Test
    public void scopedPreferencesDoNotLeakAcrossZonesForSameUser() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);
        MemoryScope driver = new MemoryScope("shared-account", VehicleZone.DRIVER);
        MemoryScope passenger = new MemoryScope("shared-account", VehicleZone.PASSENGER);

        seed(store, driver, "temperature", "23");
        seed(store, passenger, "temperature", "26");

        assertEquals("23", store.getPreference(driver, "temperature"));
        assertEquals("26", store.getPreference(passenger, "temperature"));
        assertTrue("legacy GLOBAL scope must not silently expose zone-scoped data",
                store.getAllPreferences("shared-account").isEmpty());
    }

    @Test
    public void getAllPreferencesExcludesEpochRow() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

        seed(store, "demo-driver", "key_a", "value-a");
        store.bumpEpoch();  // 写入 epoch 行到 (demo-driver, "global", "preference", "__epoch__")?
                           // 不——epoch 行在 __system__ 用户下,不会污染 demo-driver getAll。
        seed(store, "demo-driver", "key_b", "value-b");

        Map<String, String> all = store.getAllPreferences("demo-driver");
        assertEquals(2, all.size());
        assertEquals("value-a", all.get("key_a"));
        assertEquals("value-b", all.get("key_b"));
        assertFalse("epoch 行绝不能暴露给 caller", all.containsKey("__epoch__"));
    }

    @Test
    public void clearRemovesUserPreferences() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

        seed(store, "demo-driver", "k1", "v1");
        seed(store, "demo-passenger", "k2", "v2");

        store.clear("demo-driver");

        assertNull(store.getPreference("demo-driver", "k1"));
        assertEquals("demo-passenger 偏好保留", "v2",
                store.getPreference("demo-passenger", "k2"));
    }

    @Test
    public void epochDefaultsToZeroWhenNoRow() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);
        assertEquals(0L, store.currentEpoch());
    }

    @Test
    public void bumpEpochPersistsAndCrossInstanceLoads() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();

        RoomMemoryStore first = new RoomMemoryStore(dao, Runnable::run);
        first.bumpEpoch();
        first.bumpEpoch();
        first.bumpEpoch();

        assertEquals(3L, first.currentEpoch());

        // 同一 DAO(fake store 共享),新建 RoomMemoryStore——必须从 epoch 行加载到 3L
        RoomMemoryStore reloaded = new RoomMemoryStore(dao, Runnable::run);
        assertEquals("epoch 必须跨实例加载(模拟进程重启)", 3L, reloaded.currentEpoch());
    }

    @Test
    public void putPreferenceCheckedRejectsStaleEpoch() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

        store.bumpEpoch();  // current=1L
        boolean accepted = store.putPreferenceChecked("demo-driver", "k", "v", 0L /* stale */);

        assertFalse("stale epoch 必须拒绝", accepted);
        assertNull("stale 写入不落盘", store.getPreference("demo-driver", "k"));
    }

    @Test
    public void putPreferenceCheckedAcceptsCurrentEpoch() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

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

        seed(store, "demo-driver", "k1", "v1");
        seed(store, "demo-passenger", "k2", "v2");
        assertEquals(0L, store.currentEpoch());

        long newEpoch = store.clearUserDataAndBump("demo-driver", "demo-passenger");

        assertEquals("epoch 推进到 1L", 1L, newEpoch);
        assertEquals(1L, store.currentEpoch());
        assertNull("demo-driver 偏好清空", store.getPreference("demo-driver", "k1"));
        assertNull("demo-passenger 偏好清空", store.getPreference("demo-passenger", "k2"));
        assertEquals("每次持久化变更都有事务", 3, txInvocations.get());
    }

    @Test
    public void clearUserDataAndBumpRollsBackOnTransactionFailure() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        // 注入失败 transaction runner——模拟 Room transaction 异常
        RoomMemoryStore.TransactionRunner failingTx = body -> {
            throw new RuntimeException("simulated SQLCipher failure");
        };
        RoomMemoryStore store = new RoomMemoryStore(dao, failingTx);

        MemoryRecordEntity seeded = new MemoryRecordEntity();
        seeded.userId = "demo-driver"; seeded.zone = "global"; seeded.layer = "preference";
        seeded.key = "k1"; seeded.value = "v1";
        dao.upsert(seeded);
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
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);

        seed(store, "demo-driver", "key", "value");
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

    @Test
    public void failedEpochInitializationNeverPretendsEpochZero() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        dao.failEpochReads = true;
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);
        try {
            store.currentEpoch();
            org.junit.Assert.fail("epoch read failure must be visible");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("epoch"));
        }
        assertTrue(dao.store.isEmpty());
    }

    @Test
    public void rejectedInitializationExecutorNeverEnablesWrites() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run,
                task -> { throw new java.util.concurrent.RejectedExecutionException("closed"); });
        try {
            store.currentEpoch();
            org.junit.Assert.fail("rejected executor must be visible");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("epoch"));
        }
        assertTrue(dao.store.isEmpty());
    }

    @Test
    public void secondStoreRejectsStaleWriteAndBumpsMonotonically() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore first = new RoomMemoryStore(dao, Runnable::run);
        RoomMemoryStore second = new RoomMemoryStore(dao, Runnable::run);
        assertEquals(1L, first.clearUserDataAndBump("demo-driver", "demo-passenger"));
        assertFalse(second.putPreferenceChecked(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "preferred_temperature", "24", 0L));
        assertEquals(2L, second.clearUserDataAndBump("demo-driver", "demo-passenger"));
        assertEquals("2", dao.queryByKey(RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY).value);
    }

    @Test
    public void deletePreferenceOnlyAffectsOneKeyAndZone() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, Runnable::run);
        MemoryScope driver = new MemoryScope("shared", VehicleZone.DRIVER);
        MemoryScope passenger = new MemoryScope("shared", VehicleZone.PASSENGER);
        store.putPreferenceChecked(driver, "home_address", "北京", 0L);
        store.putPreferenceChecked(driver, "preferred_temperature", "24", 0L);
        store.putPreferenceChecked(passenger, "home_address", "上海", 0L);
        assertEquals(MemoryDeleteOutcome.STALE_EPOCH,
                store.deletePreferenceDetailed(driver, "home_address", 1L));
        assertTrue(store.deletePreferenceChecked(driver, "home_address", 0L));
        assertNull(store.getPreference(driver, "home_address"));
        assertEquals("24", store.getPreference(driver, "preferred_temperature"));
        assertEquals("上海", store.getPreference(passenger, "home_address"));
    }

    /** Fake in-memory DAO——语义对齐真实 Room _Impl.java。 */
    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) {
            return store.remove(userId + "@" + zone + "#" + layer + "/" + key) == null ? 0 : 1;
        }

        final Map<String, MemoryRecordEntity> store = new LinkedHashMap<>();
        boolean failEpochReads;

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
            if (failEpochReads && RoomMemoryStore.SYSTEM_USER.equals(userId)) {
                throw new IllegalStateException("simulated epoch query failure");
            }
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
    private static void seed(MemoryStore store, String userId, String key, String value) {
        assertTrue(store.putPreferenceChecked(userId, key, value, store.currentEpoch()));
    }

    private static void seed(MemoryStore store, MemoryScope scope, String key, String value) {
        assertTrue(store.putPreferenceChecked(scope, key, value, store.currentEpoch()));
    }

}
