package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.tool.ToolResult;
import com.matrix.agent.task.persistence.EpisodicMemorySink;
import com.matrix.agent.task.prompt.DefaultPromptBuilder;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/** Exercises memory on a dedicated SQLCipher file; never opens the app's production database. */
@RunWith(AndroidJUnit4.class)
public final class MemorySqlCipherDeviceTest {
    private Context context;
    private String dbName;
    private MatrixDatabase database;
    private final byte[] passphrase = "memory-device-test-passphrase-32".getBytes(StandardCharsets.UTF_8);

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dbName = "memory-test-" + UUID.randomUUID() + ".db";
        System.loadLibrary("sqlcipher");
        database = open();
    }

    @After public void tearDown() {
        if (database != null) database.close();
        if (context != null && dbName != null) context.deleteDatabase(dbName);
        Arrays.fill(passphrase, (byte) 0);
    }

    @Test public void saveRecallDeleteAndClearAreScopedAndEncrypted() throws Exception {
        RoomMemoryStore store = new RoomMemoryStore(database, null);
        RoomMemoryWriter writer = new RoomMemoryWriter(database.sessionHistoryDao(),
                database.memoryRecordDao(), null, database::runInTransaction);
        MemoryScope driver = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        MemoryScope passenger = new MemoryScope("demo-driver", VehicleZone.PASSENGER);
        assertTrue(store.putPreferenceChecked(driver, "preferred_temperature", "24", store.currentEpoch()));
        assertTrue(store.putPreferenceChecked(passenger, "preferred_temperature", "26", store.currentEpoch()));
        assertTrue(writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "session-test", store.currentEpoch()), "allergy.peanut", "花生", 1.0));
        assertEquals("24", store.getPreference(driver, "preferred_temperature"));
        assertEquals("26", store.getPreference(passenger, "preferred_temperature"));
        assertEquals("花生", writer.readSemantic(MemoryTestRequests.from("demo-driver", "driver", null, 0L), "allergy.peanut"));
        assertNull(writer.readSemantic(MemoryTestRequests.from("demo-driver", "passenger", null, 0L), "allergy.peanut"));
        assertNull(writer.readSemantic(MemoryTestRequests.from("demo-passenger", "driver", null, 0L), "allergy.peanut"));

        MemoryRouter router = new MemoryRouter((scope, session, query, limit) -> List.of(),
                (scope, query, limit) -> List.of(),
                new SemanticMemorySourceImpl(database.memoryRecordDao()),
                new LegacyPreferenceMemorySource(store));
        List<MemorySnippet> recalled = router.recall(driver, "session-test", "我对花生过敏", 8);
        assertTrue(recalled.stream().anyMatch(item -> "allergy.peanut".equals(item.getKey())));
        String prompt = DefaultPromptBuilder.formatRecalledMemory(recalled);
        assertTrue(prompt.contains("allergy.peanut"));
        assertFalse(prompt.contains("花生"));

        assertEquals(MemoryDeleteOutcome.DELETED,
                writer.deleteSemanticDetailed(MemoryTestRequests.from("demo-driver", "DRIVER", null, store.currentEpoch()), "allergy.peanut"));
        assertNull(writer.readSemantic(MemoryTestRequests.from("demo-driver", "driver", null, 0L), "allergy.peanut"));
        assertTrue(router.recall(driver, "session-test", "我对花生过敏", 8).stream()
                .noneMatch(item -> "allergy.peanut".equals(item.getKey())));
        long oldEpoch = store.currentEpoch();
        store.clearUsersAndBump(List.of("demo-driver"));
        assertNull(store.getPreference(driver, "preferred_temperature"));
        assertNull(store.getPreference(passenger, "preferred_temperature"));
        assertFalse(store.putPreferenceChecked(driver, "preferred_temperature", "25", oldEpoch));

        File file = context.getDatabasePath(dbName);
        byte[] header = new byte[16];
        try (FileInputStream input = new FileInputStream(file)) {
            assertEquals(16, input.read(header));
        }
        assertFalse("SQLCipher file must not have a plaintext SQLite header",
                "SQLite format 3\0".equals(new String(header, StandardCharsets.US_ASCII)));

        database.close();
        database = open();
        RoomMemoryStore reopened = new RoomMemoryStore(database, null);
        assertTrue(reopened.currentEpoch() > oldEpoch);
        assertNull(reopened.getPreference(driver, "preferred_temperature"));
    }

    @Test public void verifiedDestinationCanBeRecalledAndForgottenWithinOwnerScope() {
        RoomMemoryStore store = new RoomMemoryStore(database, null);
        RoomMemoryWriter writer = new RoomMemoryWriter(database.sessionHistoryDao(),
                database.memoryRecordDao(), null, database::runInTransaction);
        AgentRequest request = AgentRequest.builder("导航到人民广场", Actor.DRIVER)
                .sessionId("episodic-device-test").occupantZone(VehicleZone.DRIVER)
                .epoch(store.currentEpoch()).build();
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS, "navigation.start_route",
                "已开始导航", Map.of("navigation.destination", "人民广场"), true, 1L);
        ToolCall call = new ToolCall("navigation.start_route", Map.of("destination", "人民广场"));
        Trajectory trajectory = new Trajectory(System.currentTimeMillis());
        trajectory.addIteration(new AgentIteration(1, AgentMessage.assistant("", List.of(call)),
                List.of(), List.of(ToolObservation.of(call, result)), List.of(), 1L));
        trajectory.finish(StopReason.DONE, 1L, 1);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(), TaskState.SUCCEEDED,
                StopReason.DONE, trajectory, 1L, List.of(result));
        new EpisodicMemorySink(writer).writeEpisodicOnTerminal(request, outcome, request.getEpoch());

        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(database.sessionHistoryDao());
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        List<MemorySnippet> snippets = source.recallEpisodic(scope, "上次导航去哪", 5);
        assertEquals(1, snippets.size());
        String eventId = EpisodicFactCodec.eventIdFor(request, trajectory.getStartedAtMillis());
        assertTrue(snippets.get(0).getKey().endsWith(eventId));
        assertFalse(DefaultPromptBuilder.formatRecalledMemory(snippets).contains("人民广场"));
        String detail = writer.readEpisodic(request, eventId);
        assertTrue(detail.contains("人民广场"));
        MockCapabilityProvider provider = new MockCapabilityProvider(store, writer);
        ToolResult fetched = provider.execute(AgentRequest.builder("上次导航去哪", Actor.DRIVER)
                        .occupantZone(VehicleZone.DRIVER).build(),
                new ToolCall("memory.episodic.get", Map.of("event_id", eventId)));
        assertEquals(ToolResult.Status.SUCCESS, fetched.getStatus());
        assertTrue(fetched.getMessage().contains("人民广场"));
        AgentRequest other = AgentRequest.builder("上次导航去哪", Actor.PASSENGER)
                .occupantZone(VehicleZone.DRIVER).build();
        assertNull(writer.readEpisodic(other, eventId));
        store.clearUsersAndBump(List.of("unrelated-owner"));
        assertEquals(MemoryDeleteOutcome.TARGET_NOT_AUTHORIZED, writer.deleteEpisodic(request, eventId));
        AgentRequest staleDelete = AgentRequest.builder("忘记事件 " + eventId, Actor.DRIVER)
                .epoch(request.getEpoch()).build();
        assertEquals(MemoryDeleteOutcome.STALE_EPOCH, writer.deleteEpisodic(staleDelete, eventId));
        AgentRequest fresh = AgentRequest.builder("忘记事件 " + eventId, Actor.DRIVER)
                .sessionId(request.getSessionId()).occupantZone(VehicleZone.DRIVER)
                .epoch(store.currentEpoch()).build();
        ToolResult deleted = provider.execute(fresh,
                new ToolCall("memory.episodic.delete", Map.of("event_id", eventId)));
        assertEquals(ToolResult.Status.SUCCESS, deleted.getStatus());
        assertNull(writer.readEpisodic(request, eventId));
        assertTrue(source.recallEpisodic(scope, "上次导航去哪", 5).isEmpty());
    }

    private MatrixDatabase open() {
        return Room.databaseBuilder(context, MatrixDatabase.class, dbName)
                .openHelperFactory(new SupportOpenHelperFactory(passphrase.clone()))
                .allowMainThreadQueries()
                .build();
    }
}
