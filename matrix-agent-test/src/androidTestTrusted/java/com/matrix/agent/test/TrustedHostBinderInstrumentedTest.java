package com.matrix.agent.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentServiceInfo;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.client.MatrixAgent;
import com.matrix.agent.client.MatrixAgentManager;
import com.matrix.agent.client.VoiceManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs from a separately installed trusted APK using only the published service library. */
@RunWith(AndroidJUnit4.class)
public final class TrustedHostBinderInstrumentedTest {
    @Test public void trustedClientNegotiatesAndGetsEveryDomainBinder() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());
            MatrixAgentManager manager = agent.getAgentManager();
            assertNotNull(manager);
            AgentServiceInfo info = manager.getServiceInfo();
            assertNotNull(info);
            // Voice Binder is always present, but a release image advertises its voice feature
            // only after an OEM VoiceRuntime is supplied. The other Stage-B domains are
            // mandatory, and the service must never advertise unknown feature bits.
            int mandatoryFeatures = MatrixServiceConstants.ALL_STAGE_B_FEATURES
                    & ~MatrixServiceConstants.FEATURE_VOICE_DOMAIN;
            assertEquals(mandatoryFeatures, info.featureFlags & mandatoryFeatures);
            assertEquals(0, info.featureFlags & ~MatrixServiceConstants.ALL_STAGE_B_FEATURES);
            assertNotNull(agent.getModelManager());
            assertNotNull(agent.getDownloadManager());
            assertNotNull(agent.getVoiceManager());
            AgentRequest request = new AgentRequest(UUID.randomUUID().toString(), "cross-apk",
                    "状态检查", AgentRequest.INPUT_TEXT, Locale.getDefault().toLanguageTag());
            AgentTaskHandle handle = awaitDurableTaskAdmission(manager, request);
            assertNotNull(handle);
            assertTrue(handle.errorCode == MatrixErrorCode.SUCCESS
                    || handle.errorCode == MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
        } finally {
            agent.release();
        }
    }

    /** Recovery is fail-closed after a process replacement; retry uses one idempotency key. */
    private static AgentTaskHandle awaitDurableTaskAdmission(MatrixAgentManager manager,
            AgentRequest request) {
        long deadline = SystemClock.elapsedRealtime() + 4_000L;
        AgentTaskHandle handle;
        do {
            handle = manager.submit(request, event -> { });
            if (handle != null && handle.errorCode != MatrixErrorCode.SERVICE_NOT_READY) return handle;
            SystemClock.sleep(100L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return handle;
    }

    @Test public void trustedClientReadsAndUnsubscribesVoiceStatus() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());
            VoiceManager voice = agent.getVoiceManager();
            assertNotNull(voice);
            assertNotNull(voice.getStatus());
            CountDownLatch delivered = new CountDownLatch(1);
            AutoCloseable subscription = voice.subscribeStatus(status -> delivered.countDown());
            try {
                assertTrue("voice status callback was not delivered", delivered.await(2, TimeUnit.SECONDS));
            } finally {
                subscription.close();
            }
        } finally {
            agent.release();
        }
    }
}
