package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

/**
 * RoomMemoryMigrator 失败保留 SP 契约测试。
 *
 * <p>评审发现旧实现 per-entry try/catch + 循环外无条件 clear(),单条写入失败仍清空 SP 导致数据丢失。
 * 本测试验证:(1) 任一 upsert 失败 transaction rollback + SP 不清空;(2) 下次启动重试成功;
 * (3) epoch 与偏好同 transaction(任一失败互相回滚)。
 */
public final class RoomMemoryMigratorFailureTest {

    @Test
    public void spRetainedWhenTransactionThrowsOnPreference() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.pref.a", "v1");
        spData.put("demo-driver.pref.b", "v2");  // 这条会触发 dao 抛异常
        FailingSpSource sp = new FailingSpSource(spData);
        FailingMemoryRecordDao dao = new FailingMemoryRecordDao();
        dao.failOnKey("demo-driver.pref.b");

        new RoomMemoryMigrator(sp, dao, dao.transactionalRunner()).migrate();

        assertFalse("transaction 失败时 SP 不应清空", sp.cleared);
        assertEquals("Room 内不应有任何写入(rollback)", 0, dao.store.size());
        assertEquals("SP 数据应保留供下次启动重试", 2, sp.data.size());
    }

    @Test
    public void retryOnNextStartupSucceedsAfterTransientFailure() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.pref.a", "v1");
        spData.put("demo-driver.pref.b", "v2");
        FailingSpSource sp = new FailingSpSource(spData);
        FailingMemoryRecordDao dao = new FailingMemoryRecordDao();
        dao.failOnKey("demo-driver.pref.b");

        // 第一次启动:迁移失败,SP 保留
        new RoomMemoryMigrator(sp, dao, dao.transactionalRunner()).migrate();
        assertFalse("第一次启动失败时 SP 保留", sp.cleared);

        // 第二次启动:dao 不再失败,迁移成功,SP 清空
        dao.clearFailures();
        new RoomMemoryMigrator(sp, dao, dao.transactionalRunner()).migrate();
        assertTrue("第二次启动成功时 SP 清空", sp.cleared);
        assertEquals("两条偏好都迁到 Room", 2, dao.store.size());
    }

    @Test
    public void epochAndPreferenceAreAtomic() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.pref.a", "v1");
        spData.put("__epoch__", 42L);
        FailingSpSource sp = new FailingSpSource(spData);
        FailingMemoryRecordDao dao = new FailingMemoryRecordDao();
        dao.failOnKey("__epoch__");  // epoch 写入失败

        new RoomMemoryMigrator(sp, dao, dao.transactionalRunner()).migrate();

        assertFalse("epoch 失败时 SP 保留(整个 transaction rollback)", sp.cleared);
        assertEquals("Room 内既无偏好也无 epoch(全部 rollback)", 0, dao.store.size());
    }

    @Test
    public void spClearedWhenAllEntitiesSucceed() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.pref.a", "v1");
        spData.put("demo-passenger.pref.b", "v2");
        spData.put("__epoch__", 9L);
        FailingSpSource sp = new FailingSpSource(spData);
        FailingMemoryRecordDao dao = new FailingMemoryRecordDao();

        new RoomMemoryMigrator(sp, dao, dao.transactionalRunner()).migrate();

        assertTrue("全部成功时 SP 必须清空", sp.cleared);
        assertEquals("3 entities 全部入库(2 prefs + 1 epoch)", 3, dao.store.size());
    }

    @Test
    public void transactionRunnerExceptionAlsoRetainsSp() {
        Map<String, Object> spData = new LinkedHashMap<>();
        spData.put("demo-driver.pref.a", "v1");
        FailingSpSource sp = new FailingSpSource(spData);
        FailingMemoryRecordDao dao = new FailingMemoryRecordDao();

        // 注入抛异常的 transactionRunner(模拟 SQLCipher 不可用 / 磁盘满)
        RoomMemoryStore.TransactionRunner throwingRunner = (body) -> {
            throw new RuntimeException("simulated SQLCipher failure");
        };

        new RoomMemoryMigrator(sp, dao, throwingRunner).migrate();

        assertFalse("transactionRunner 抛异常时 SP 保留", sp.cleared);
        assertEquals("dao 没被调用(transaction 失败)", 0, dao.store.size());
    }

    private static final class FailingSpSource implements RoomMemoryMigrator.SpSource {
        final Map<String, ?> data;
        boolean cleared = false;

        FailingSpSource(Map<String, ?> data) {
            this.data = new LinkedHashMap<>(data);
        }

        @Override
        public Map<String, ?> getAll() {
            return new LinkedHashMap<>(data);
        }

        @Override
        public boolean clear() {
            data.clear();
            cleared = true;
            return true;
        }
    }

    private static final class FailingMemoryRecordDao implements MemoryRecordDao {
        final Map<String, MemoryRecordEntity> store = new LinkedHashMap<>();
        final Map<String, RuntimeException> failures = new LinkedHashMap<>();
        // transaction buffer:body 抛异常时不 commit,模拟 Room runInTransaction rollback 语义
        final List<MemoryRecordEntity> pending = new ArrayList<>();
        boolean inTransaction = false;

        void failOnKey(String spStyleKey) {
            failures.put(spStyleKey, new RuntimeException("simulated upsert failure"));
        }

        void clearFailures() {
            failures.clear();
        }

        /** 真实 transaction runner:body 抛异常时不 commit pending 写入。 */
        RoomMemoryStore.TransactionRunner transactionalRunner() {
            return (body) -> {
                inTransaction = true;
                pending.clear();
                try {
                    body.run();
                    // 全部成功 → commit
                    for (MemoryRecordEntity e : pending) {
                        store.put(storageKey(e), e);
                    }
                } finally {
                    pending.clear();
                    inTransaction = false;
                }
            };
        }

        private static String storageKey(MemoryRecordEntity e) {
            return e.userId + "@" + e.zone + "#" + e.layer + "/" + e.key;
        }

        @Override
        public void upsert(MemoryRecordEntity entity) {
            String spStyle = entity.userId + "." + entity.key;
            if (failures.containsKey(spStyle)) {
                throw failures.get(spStyle);
            }
            if (RoomMemoryStore.EPOCH_KEY.equals(entity.key)
                    && failures.containsKey(RoomMemoryStore.EPOCH_KEY)) {
                throw failures.get(RoomMemoryStore.EPOCH_KEY);
            }
            if (inTransaction) {
                pending.add(entity);
            } else {
                store.put(storageKey(entity), entity);
            }
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
