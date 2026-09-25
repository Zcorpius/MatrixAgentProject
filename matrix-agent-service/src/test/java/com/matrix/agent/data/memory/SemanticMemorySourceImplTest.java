package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

/**
 * SemanticMemorySourceImpl JVM 测试——关键词召回 + score 排序 + fail-open。
 */
public final class SemanticMemorySourceImplTest {

    @Test
    public void recallMatchesUserTextAgainstKeyAndValue() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "semantic", "preferred_route", "高速优先", 1.0, 100L);
        dao.add("user-d", "driver", "semantic", "preferred_temp", "24度", 1.0, 200L);
        dao.add("user-d", "driver", "semantic", "home_address", "北京", 0.5, 300L);
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "走高速回家", 5);

        // "高速" 命中 preferred_route value, "home" 不命中(中文 home 不匹配)
        // preferred_route: score = 1.0 + "高速"命中 value + "home"未命中 + "走"过滤掉(1字中文不算 token)
        // 关键词:走高速回家 → tokenize → 走(单字符中文,不进), 高速(2字), 回家(2字), → {高速, 回家}
        // preferred_route (key=preferred_route, value=高速优先):
        //   "高速" 在 value → +1.0; "回家" 不在 → 总分 1.0 + 1.0 = 2.0
        // home_address (key=home_address, value=北京):
        //   "高速" 不在; "回家" 不在 → 0.5 (静态)
        // preferred_temp: 0.5 + "高速"不在 + "回家"不在 → 1.0(实际0score字段默认0)
        assertEquals("只返回有检索证据的候选", 2, result.size());
        assertTrue("最高分是 preferred_route (高速命中)", result.get(0).getKey().contains("preferred_route"));
    }

    @Test
    public void maxItemsCapsResult() {
        FakeDao dao = new FakeDao();
        for (int i = 0; i < 8; i++) {
            dao.add("user-d", "driver", "semantic", "key-" + i, "value-" + i, 1.0, 100L + i);
        }
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "value", 3);

        assertEquals("maxItems=3 截断", 3, result.size());
    }

    @Test
    public void noMatchReturnsEmptyRegardlessOfStaticScore() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "semantic", "k1", "v1", 0.3, 100L);
        dao.add("user-d", "driver", "semantic", "k2", "v2", 0.8, 200L);
        dao.add("user-d", "driver", "semantic", "k3", "v3", 0.5, 300L);
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        // 无 query → 不提供相关性证据，不能用静态 score 补满名额。
        List<MemorySnippet> result = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "", 5);

        assertTrue(result.isEmpty());
    }

    @Test
    public void englishSubstringIsNotEvidence() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "semantic", "fact.vehicle", "car", 1.0, 100L);
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao);

        assertTrue(source.recallSemantic(new MemoryScope("user-d", VehicleZone.DRIVER),
                "carpet", 5).isEmpty());
    }

    @Test
    public void daoExceptionReturnsEmptyFailOpen() {
        ThrowingDao dao = new ThrowingDao();
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);

        assertTrue("Dao 异常返回空 list", result.isEmpty());
    }

    @Test
    public void differentUserZoneReturnsDifferentResults() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "semantic", "driver-key", "driver memory", 1.0, 100L);
        dao.add("user-p", "passenger", "semantic", "passenger-key", "passenger memory", 1.0, 200L);
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        List<MemorySnippet> driverResult = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "memory", 5);
        List<MemorySnippet> passengerResult = source.recallSemantic(
                new MemoryScope("user-p", VehicleZone.PASSENGER), "memory", 5);

        assertEquals("driver 命中 driver-key", 1, driverResult.size());
        assertTrue(driverResult.get(0).getKey().contains("driver-key"));
        assertEquals("passenger 命中 passenger-key", 1, passengerResult.size());
        assertTrue(passengerResult.get(0).getKey().contains("passenger-key"));
    }

    @Test
    public void tokenizeFiltersShortNonChineseTokens() {
        java.util.Set<String> tokens = SemanticMemorySourceImpl.tokenize("a 大测");
        assertTrue(tokens.contains("大测"));
        assertTrue(!tokens.contains("大"));
    }

    private static final class FakeDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) { return 0; }

        final List<MemoryRecordEntity> store = new ArrayList<>();

        void add(String userId, String zone, String layer, String key, String value,
                double score, long capturedAt) {
            MemoryRecordEntity e = new MemoryRecordEntity();
            e.userId = userId;
            e.zone = zone;
            e.layer = layer;
            e.key = key;
            e.value = value;
            e.score = score;
            e.capturedAtMs = capturedAt;
            store.add(e);
        }

        @Override
        public void upsert(MemoryRecordEntity entity) {
            store.add(entity);
        }

        @Override
        public List<MemoryRecordEntity> queryByUserZoneLayer(String userId, String zone, String layer) {
            List<MemoryRecordEntity> result = new ArrayList<>();
            for (MemoryRecordEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone) && layer.equals(e.layer)) {
                    result.add(e);
                }
            }
            return result;
        }

        @Override
        public MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key) {
            for (MemoryRecordEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone) && layer.equals(e.layer) && key.equals(e.key)) {
                    return e;
                }
            }
            return null;
        }

        @Override
        public int deleteByUser(String userId) {
            int removed = 0;
            for (int i = store.size() - 1; i >= 0; i--) {
                if (userId.equals(store.get(i).userId)) {
                    store.remove(i);
                    removed++;
                }
            }
            return removed;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (int i = store.size() - 1; i >= 0; i--) {
                MemoryRecordEntity e = store.get(i);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(i);
                    removed++;
                }
            }
            return removed;
        }
    }

    private static final class ThrowingDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) { return 0; }

        @Override public void upsert(MemoryRecordEntity entity) { }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String userId, String zone, String layer) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key) {
            return null;
        }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }
}
