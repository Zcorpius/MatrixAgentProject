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

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.identity.VehicleZone;

/**
 * RoomMemoryWriter 真实 Room _Impl.java SQL 验证
 * (in-memory,不走 SQLCipher)。
 *
 * <p>JVM 契约测试 {@code RoomMemoryWriterEpisodicTest} 用 fake DAO 验证 caller 行为;
 * 真实 SQL / @Insert(REPLACE) 幂等 / queryByKey 索引在 emulator 上首次跑通由本类负责。
 *
 * <p>覆盖:
 * <ul>
 *   <li>memory.semantic.save → writeSemantic 落 memory_record 表(layer="semantic");</li>
 *   <li>memory.semantic.get → readSemantic 通过 queryByKey 4 参索引读回;</li>
 *   <li>跨 RoomMemoryWriter 实例(模拟 process 重启)值仍在;</li>
 *   <li>writeSemantic 同 (user, zone, layer, key) REPLACE 幂等;</li>
 *   <li>userId/zone 隔离:不同 (user, zone) 同 key 互不影响。</li>
 * </ul>
 *
 * <p>writeSemantic 接口加 requestEpoch 参数。RoomMemoryWriter 第 4 参
 * 是 {@link RoomMemoryStore.TransactionRunner},androidTest 传 {@code db::runInTransaction}
 * (真实 Room 事务)。默认 __system__ epoch 行不存在 → readEpochFromSystemRow=0,
 * requestEpoch=0 → 写入成功。
 */
@RunWith(AndroidJUnit4.class)
public final class MemorySemanticSaveIntegrationTest {
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
    public void writeSemanticPersistsAndReadSemanticReturns() {
        RoomMemoryWriter writer = new RoomMemoryWriter(
                db.sessionHistoryDao(), db.memoryRecordDao(), null, db::runInTransaction);

        boolean accepted = writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-001", 0L), "allergy.peanut", "严重过敏", 1.0);
        assertTrue(accepted);

        String value = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "allergy.peanut");
        assertEquals("严重过敏", value);
        assertEquals("driver", db.memoryRecordDao().queryByKey("demo-driver", "driver",
                "semantic", "allergy.peanut").zone);
        assertEquals("allergy.peanut", new SemanticMemorySourceImpl(db.memoryRecordDao())
                .recallSemantic(new MemoryScope("demo-driver", VehicleZone.DRIVER),
                        "我对花生过敏吗", 5).get(0).getKey());
    }

    @Test
    public void valueSurvivesWriterRecreate() {
        RoomMemoryWriter writer1 = new RoomMemoryWriter(
                db.sessionHistoryDao(), db.memoryRecordDao(), null, db::runInTransaction);
        writer1.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-001", 0L), "fact.daughter_name", "小红", 1.0);

        // 模拟 process 重启:writer 重新构造,database 仍是同一个 Room 实例
        RoomMemoryWriter writer2 = new RoomMemoryWriter(
                db.sessionHistoryDao(), db.memoryRecordDao(), null, db::runInTransaction);
        String value = writer2.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "fact.daughter_name");

        assertEquals("小红", value);
    }

    @Test
    public void writeSemanticIsIdempotentReplace() {
        RoomMemoryWriter writer = new RoomMemoryWriter(
                db.sessionHistoryDao(), db.memoryRecordDao(), null, db::runInTransaction);

        writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-001", 0L), "work.role", "产品经理", 1.0);
        writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-002", 0L), "work.role", "工程师", 1.0);

        String value = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "work.role");
        assertEquals("REPLACE 应覆盖旧值", "工程师", value);
    }

    @Test
    public void userZoneIsolation() {
        RoomMemoryWriter writer = new RoomMemoryWriter(
                db.sessionHistoryDao(), db.memoryRecordDao(), null, db::runInTransaction);
        writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "s1", 0L), "allergy.peanut", "过敏", 1.0);
        writer.writeSemantic(MemoryTestRequests.from("demo-passenger", "PASSENGER", "s2", 0L), "allergy.peanut", "无", 1.0);

        String driverValue = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "allergy.peanut");
        String passengerValue = writer.readSemantic(MemoryTestRequests.from("demo-passenger", "PASSENGER", null, 0L), "allergy.peanut");

        assertEquals("driver / passenger 同 key 隔离", "过敏", driverValue);
        assertEquals("driver / passenger 同 key 隔离", "无", passengerValue);
    }

    @Test
    public void readSemanticReturnsNullWhenAbsent() {
        RoomMemoryWriter writer = new RoomMemoryWriter(
                db.sessionHistoryDao(), db.memoryRecordDao(), null, db::runInTransaction);

        String value = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "not.existing");
        assertNull(value);
    }
}
