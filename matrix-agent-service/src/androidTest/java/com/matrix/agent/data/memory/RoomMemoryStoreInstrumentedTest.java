package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

/**
 * RoomMemoryStore 真实 Room _Impl.java SQL 验证(in-memory,不走 SQLCipher)。
 *
 * <p>JVM 契约测试 {@code RoomMemoryStoreTest} 用 fake DAO 验证 caller 行为;
 * 真实 SQL / Transaction 隔离 / Room runInTransaction 在 emulator 上首次跑通由本类负责。
 *
 * <p>覆盖:
 * <ul>
 *   <li>preference 走 memory_record 表(layer="preference",zone="global");</li>
 *   <li>epoch 持久化到 __system__ 行,跨 RoomMemoryStore 实例加载;</li>
 *   <li>putPreferenceChecked epoch 校验真实生效(写库 vs 拒绝);</li>
 *   <li>clearUserDataAndBump 通过 database.runInTransaction 原子执行;</li>
 *   <li>getAllPreferences 不暴露 __system__ epoch 行。</li>
 * </ul>
 *
 * <p>用 {@link Room#inMemoryDatabaseBuilder} 绕过 SQLCipher + AndroidKeyStore,
 * 每个测试方法独立 database instance(互不污染)。
 */
@RunWith(AndroidJUnit4.class)
public final class RoomMemoryStoreInstrumentedTest {
    private MatrixDatabase db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MatrixDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
    }

    @Test
    public void putAndGetPreferenceRoundTripOnRealRoom() {
        MemoryRecordDao dao = db.memoryRecordDao();
        MemoryStore store = new RoomMemoryStore(dao, db::runInTransaction);

        store.putPreference("demo-driver", "user.preference.temperature", "24");

        assertEquals("24", store.getPreference("demo-driver", "user.preference.temperature"));
        assertNull(store.getPreference("demo-driver", "missing"));
    }

    @Test
    public void epochPersistedAcrossInstancesOnRealRoom() {
        MemoryRecordDao dao = db.memoryRecordDao();
        RoomMemoryStore first = new RoomMemoryStore(dao, db::runInTransaction);
        first.bumpEpoch();
        first.bumpEpoch();

        // 同一 db,新建 RoomMemoryStore——必须从 epoch 行加载到 2L
        RoomMemoryStore reloaded = new RoomMemoryStore(dao, db::runInTransaction);
        assertEquals("epoch 跨实例加载(模拟进程重启)", 2L, reloaded.currentEpoch());
    }

    @Test
    public void putPreferenceCheckedRejectsStaleOnRealRoom() {
        MemoryRecordDao dao = db.memoryRecordDao();
        MemoryStore store = new RoomMemoryStore(dao, db::runInTransaction);

        store.bumpEpoch();  // current=1L
        boolean accepted = store.putPreferenceChecked(
                "demo-driver", "k", "v", 0L /* stale */);

        assertFalse("stale epoch 必须拒绝", accepted);
        assertNull("stale 写入不落盘", store.getPreference("demo-driver", "k"));
    }

    @Test
    public void clearUserDataAndBumpAtomicOnRealRoom() {
        MemoryRecordDao dao = db.memoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, db::runInTransaction);

        store.putPreference("demo-driver", "k1", "v1");
        store.putPreference("demo-passenger", "k2", "v2");
        assertEquals(0L, store.currentEpoch());

        long newEpoch = store.clearUserDataAndBump("demo-driver", "demo-passenger");

        assertEquals(1L, newEpoch);
        assertEquals(1L, store.currentEpoch());
        assertNull("demo-driver 偏好清空", store.getPreference("demo-driver", "k1"));
        assertNull("demo-passenger 偏好清空", store.getPreference("demo-passenger", "k2"));

        // epoch 行保留(在 __system__ 用户下,不被 deleteByUser("demo-driver") 触及)
        MemoryRecordEntity epochRow = dao.queryByKey(
                RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        assertEquals("1", epochRow.value);
    }

    @Test
    public void getAllPreferencesDoesNotLeakEpochRow() {
        MemoryRecordDao dao = db.memoryRecordDao();
        RoomMemoryStore store = new RoomMemoryStore(dao, db::runInTransaction);

        store.putPreference("demo-driver", "key-a", "value-a");
        store.bumpEpoch();  // 写 epoch 行
        store.putPreference("demo-driver", "key-b", "value-b");

        Map<String, String> all = store.getAllPreferences("demo-driver");
        assertEquals(2, all.size());
        assertFalse("epoch 行不能暴露给 caller", all.containsKey("__epoch__"));
    }
}
