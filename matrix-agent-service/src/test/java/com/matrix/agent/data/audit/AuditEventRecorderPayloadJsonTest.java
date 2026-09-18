package com.matrix.agent.data.audit;
import com.matrix.agent.task.steer.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * AuditEventRecorder payloadJson 合法 JSON 契约测试。
 *
 * <p>评审发现旧实现仅 replace 双引号,反斜杠 / 换行 / 控制字符 / Map.toString() 都会破坏 JSON 结构。
 * 本测试验证所有 recordXxx 路径产生的 payloadJson 都能通过 new JSONObject(payloadJson) 解析,
 * 含特殊字符的 preview 也正确 escape。
 */
public final class AuditEventRecorderPayloadJsonTest {

    @Test
    public void preToolPayloadIsValidJson() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("temperature", 24);
        args.put("zone", "DRIVER");
        recorder.recordPreTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "climate.set_temperature", args, null);

        assertEquals(1, dao.store.size());
        AuditEventEntity row = dao.store.get(0);
        assertEquals(AuditEventTypes.PRE_TOOL, row.type);
        JSONObject parsed = new JSONObject(row.payloadJson);
        assertEquals("climate.set_temperature", parsed.getString("tool"));
        JSONObject argsParsed = parsed.getJSONObject("args");
        assertEquals(24, argsParsed.getInt("temperature"));
        assertEquals("DRIVER", argsParsed.getString("zone"));
    }

    @Test
    public void postToolPayloadIsValidJsonWithSpecialChars() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        // 含双引号、反斜杠、换行、控制字符 —— 旧实现会破坏 JSON
        String tricky = "导航到 \"Beijing\\Chaoyang\"\n线\t1";
        recorder.recordPostTool("req-2", "demo-driver", "DRIVER", "DRIVER",
                "navigation.start", "EXECUTION_FAILED", false, 1234L, tricky);

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("navigation.start", parsed.getString("tool"));
        assertEquals("EXECUTION_FAILED", parsed.getString("status"));
        assertEquals(false, parsed.getBoolean("verified"));
        assertEquals(1234L, parsed.getLong("durationMs"));
        // 关键:特殊字符 escape 后 round-trip 仍等于原值
        assertEquals(tricky, parsed.getString("result"));
    }

    @Test
    public void policyPayloadIsValidJson() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        recorder.recordPolicyDecision("req-3", "demo-driver", "DRIVER", "DRIVER",
                "climate.set_temperature", "PARAMETER",
                "数值越界:期望 [16,30],实际 99\n下一行\t控制字符");

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("climate.set_temperature", parsed.getString("tool"));
        assertEquals("PARAMETER", parsed.getString("rejectionType"));
        assertTrue(parsed.getString("reason").contains("数值越界"));
    }

    @Test
    public void steerPayloadIsValidJson() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        recorder.recordSteer("req-4", "demo-driver", "DRIVER", "DRIVER", "FORCE_TOOL", 256);

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("FORCE_TOOL", parsed.getString("steerType"));
        assertEquals(256, parsed.getInt("payloadChars"));
    }

    @Test
    public void steerDroppedStalePayloadIsValidJson() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        recorder.recordSteerDroppedStale("stale-1", "demo-driver", "DRIVER", "DRIVER",
                "REPROMPT", 512);

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("REPROMPT", parsed.getString("steerType"));
        assertEquals(512, parsed.getInt("payloadChars"));
    }

    @Test
    public void preToolPayloadWithEmptyArgsHasNoArgsField() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        recorder.recordPreTool("req-5", "demo-driver", "DRIVER", "DRIVER",
                "vehicle_state.get", null, null);

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("vehicle_state.get", parsed.getString("tool"));
        assertTrue("空 args 时不该有 args 字段", !parsed.has("args"));
    }

    @Test
    public void preToolPayloadFallsBackToStringArgWhenMapNull() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        recorder.recordPreTool("req-6", "demo-driver", "DRIVER", "DRIVER",
                "climate.set", null, "{\"temp\":24}");

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("climate.set", parsed.getString("tool"));
        // argsJsonPreview 作为字符串放入 args 字段(不解析为嵌套 object)
        assertEquals("{\"temp\":24}", parsed.getString("args"));
    }

    @Test
    public void emptyStringValuesArePreserved() throws JSONException {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());
        recorder.recordPostTool("req-7", "demo-driver", "DRIVER", "DRIVER",
                "vehicle_state.get", "", false, 0L, "");

        JSONObject parsed = new JSONObject(dao.store.get(0).payloadJson);
        assertEquals("vehicle_state.get", parsed.getString("tool"));
        assertEquals("空 status 必须保留", "", parsed.getString("status"));
        assertEquals("空 result 必须保留", "", parsed.getString("result"));
    }

    private static java.util.concurrent.ExecutorService directExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            private boolean shutdown;
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() { shutdown = true; }
            @Override public java.util.List<Runnable> shutdownNow() { return new ArrayList<>(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }
        };
    }

    private static final class FakeDao implements AuditEventDao {
        final List<AuditEventEntity> store = new ArrayList<>();

        @Override
        public void insert(AuditEventEntity entity) {
            store.add(entity);
        }

        @Override
        public List<AuditEventEntity> queryByRequest(String requestId) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (requestId.equals(e.requestId)) result.add(e);
            }
            return result;
        }

        @Override
        public List<AuditEventEntity> queryByUserZone(String userId, String zone) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) result.add(e);
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (int i = store.size() - 1; i >= 0; i--) {
                AuditEventEntity e = store.get(i);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(i);
                    removed++;
                }
            }
            return removed;
        }
    }
}
