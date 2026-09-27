package com.matrix.agent.handoff;

import static org.junit.Assert.*;
import static com.matrix.agent.api.handoff.HandoffProtocol.*;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.conversation.ConversationStore;
import com.matrix.agent.conversation.persistence.RoomConversationStore;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.platform.media.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;

/** Real Host→Launcher→Host Binder round trip. Isolated persisted fixture, no model call or UI writes. */
@RunWith(AndroidJUnit4.class)
public final class HandoffBinderDeviceTest {
    @Test public void hundredSameRoundHandoffsMeetBudgetOnConnectedDevice() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var app = (MatrixAgentApplication) instrumentation.getTargetContext().getApplicationContext();
        var container = app.getContainer();
        var database = container.getMatrixDatabase();
        assertNotNull(database);
        var store = new RoomConversationStore(database, database::runInTransaction);
        String conversation = UUID.randomUUID().toString(), task = UUID.randomUUID().toString();
        String message = UUID.randomUUID().toString(), runtime = UUID.randomUUID().toString();
        store.createConversation(new ConversationStore.NewConversation(conversation,
                ActorUsers.USER_DRIVER, "DRIVER", "悬浮交接协议真机验收", 9));
        var submission = store.submitUserMessage(new ConversationStore.UserSubmission(conversation, message, 1,
                "协议时延验证（不执行媒体写操作）", "zh-CN", task, runtime, true, UUID.randomUUID().toString()));
        // Fixed terminal fixture prevents an interrupted instrumentation leaving a phantom active task.
        store.writeTerminal(new ConversationStore.TerminalWrite(task, 2, 0,
                UUID.randomUUID().toString(), "协议验证数据"));
        container.getHandoffContexts().bind(new HandoffContextRegistry.Binding(runtime, conversation,
                task, message, submission.sequenceNo(), ActorUsers.USER_DRIVER, "DRIVER"));
        try {
            app.startActivity(new Intent().setClassName("com.matrix.agent.launcher",
                    "com.matrix.agent.launcher.LauncherActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(2500);
            Intent target = app.getPackageManager().getLaunchIntentForPackage(MediaApp.QQMUSIC.packageName());
            assertNotNull(target);
            app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            SystemClock.sleep(1000);
            // Real Launcher is now background; the persisted fixture is presented in an overlay.
            var coordinator = container.getHandoffCoordinator();
            var initial = context(runtime);
            int first;
            try (var ignored = coordinator.begin(initial)) { first = coordinator.prepareOutcome(initial, MediaApp.QQMUSIC, INTERACT_EXISTING_APP); }
            assertEquals("first preparation must actually receive an accepted visible-window ACK", OVERLAY_READY, first);
            List<Long> times = new ArrayList<>(); int ready = 0;
            for (int i = 0; i < 100; i++) {
                var next = context(runtime); long started = SystemClock.elapsedRealtimeNanos();
                int result;
                try (var ignored = coordinator.begin(next)) { result = coordinator.prepareOutcome(next, MediaApp.QQMUSIC, INTERACT_EXISTING_APP); }
                times.add((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000);
                if (result == OVERLAY_READY) ready++;
                SystemClock.sleep(10);
            }
            Collections.sort(times);
            long p95 = times.get(94);
            Log.i("MatrixHandoffDevice", "samples=100 ready=" + ready + " p50Ms=" + times.get(49)
                    + " p95Ms=" + p95 + " maxMs=" + times.get(99));
            assertEquals("Timeouts must not disappear from the success denominator", 100, ready);
            assertTrue("same-owner real Binder P95 must be < 50 ms", p95 < 50);
        } finally { container.getHandoffContexts().unbind(runtime); }
    }
    private LaunchContext context(String runtime) {
        return new LaunchContext(runtime, UUID.randomUUID().toString(),
                SystemClock.elapsedRealtime() + 10_000, new CancellationToken());
    }
}
