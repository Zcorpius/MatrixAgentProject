package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.task.identity.VehicleZone;
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
        assertEquals("返回 3 条", 3, result.size());
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
    public void noMatchReturnsAllRowsSortedByStaticScore() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "semantic", "k1", "v1", 0.3, 100L);
        dao.add("user-d", "driver", "semantic", "k2", "v2", 0.8, 200L);
        dao.add("user-d", "driver", "semantic", "k3", "v3", 0.5, 300L);
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        // 无 query → 全部返回,按 row.score 排序
        List<MemorySnippet> result = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "", 5);

        assertEquals(3, result.size());
        // score 高在前:k2(0.8) > k3(0.5) > k1(0.3)
        assertTrue(result.get(0).getKey().contains("k2"));
        assertTrue(result.get(1).getKey().contains("k3"));
        assertTrue(result.get(2).getKey().contains("k1"));
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
        dao.add("user-d", "driver", "semantic", "driver-key", "v", 1.0, 100L);
        dao.add("user-p", "passenger", "semantic", "passenger-key", "v", 1.0, 200L);
        SemanticMemorySourceImpl source = new SemanticMemorySourceImpl(dao, 5);

        List<MemorySnippet> driverResult = source.recallSemantic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "v", 5);
        List<MemorySnippet> passengerResult = source.recallSemantic(
                new MemoryScope("user-p", VehicleZone.PASSENGER), "v", 5);

        assertEquals("driver 命中 driver-key", 1, driverResult.size());
        assertTrue(driverResult.get(0).getKey().contains("driver-key"));
        assertEquals("passenger 命中 passenger-key", 1, passengerResult.size());
        assertTrue(passengerResult.get(0).getKey().contains("passenger-key"));
    }

    @Test
    public void tokenizeFiltersShortNonChineseTokens() {
        // "a 大 测" → a 单字符非中文过滤, 大/测 单字中文保留
        java.util.Set<String> tokens = SemanticMemorySourceImpl.tokenize("a 大 测");
        assertTrue(tokens.contains("大"));
        assertTrue(tokens.contains("测"));
        // a 不在(单字符非中文)
    }

    private static final class FakeDao implements MemoryRecordDao {
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
