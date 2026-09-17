package com.matrix.agent.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.MemoryStore;
/**
 * database=null 时 MemoryRuntimeGraph.createStoreSafely 退到 InMemoryMemoryStore
 * (非持久化)+ memoryDegraded=true，绝不写入明文存储。
 *
 * <p>本测试用 Memory graph 的 package-private 静态工厂直接调用,无需实例化 AppContainer。
 * database=null 路径不调 SharedPreferences,可 JVM 测;Room 正常路径需 Android Robolectric,
 * 不在当前范围(由 androidTest MemorySemanticSaveIntegrationTest 间接覆盖)。
 */
public final class AppContainerMemoryFallbackTest {

    /**
     * database=null → 返回 InMemoryMemoryStore 实例 + memoryDegradedRef.get()==true。
     */
    @Test
    public void nullDatabaseReturnsInMemoryMemoryStoreAndSetsFlag() {
        AtomicBoolean degradedRef = new AtomicBoolean(false);
        MemoryStore store = MemoryRuntimeGraph.createStoreSafely(null, null, degradedRef, null);
        assertTrue("database=null 必须返回 InMemoryMemoryStore,actual=" + store.getClass(),
                store instanceof InMemoryMemoryStore);
        assertTrue("database=null 必须设置 memoryDegraded=true", degradedRef.get());
    }

    /**
     * database=null → memoryDegradedRef 未传入(false)时,调用后必须变 true。
     * 验证 set 操作真发生,而不是默认 true。
     */
    @Test
    public void nullDatabaseFlipsFlagFromFalseToTrue() {
        AtomicBoolean degradedRef = new AtomicBoolean(false);
        assertEquals("初始 false", false, degradedRef.get());
        MemoryRuntimeGraph.createStoreSafely(null, null, degradedRef, null);
        assertEquals("调用后必须 true", true, degradedRef.get());
    }

}
