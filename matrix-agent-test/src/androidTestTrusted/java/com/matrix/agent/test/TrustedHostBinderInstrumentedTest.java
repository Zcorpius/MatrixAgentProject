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
import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.client.MatrixAgent;
import com.matrix.agent.client.MatrixAgentManager;
import com.matrix.agent.client.VoiceManager;
import com.matrix.agent.client.VoiceSessionListener;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.voice.VoiceServiceStatus;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.List;
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

    /**
     * 真机只读验收：验证设置页、市场页和语音模型页拿到的是 Host 的持久化投影，
     * 不触发网络请求、也不改变用户已经保存的云端凭据或本地模型。
     */
    @Test public void trustedClientReadsConfiguredModelMarketAndOfflineVoiceModels() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());

            ModelRuntimeStatus runtime = agent.getModelManager().getRuntimeStatus();
            assertNotNull("model runtime projection missing", runtime);
            assertTrue("active model id missing", runtime.activeModelId != null
                    && !runtime.activeModelId.isEmpty());
            assertTrue("configured model is not ready", runtime.ready);

            List<ModelInfo> models = agent.getModelManager().listModels();
            assertTrue("model list empty", models != null && !models.isEmpty());
            boolean activeModelExposed = false;
            for (ModelInfo model : models) {
                if (model.active && runtime.activeModelId.equals(model.modelId)) {
                    activeModelExposed = true;
                    break;
                }
            }
            assertTrue("runtime active model is absent from settings projection", activeModelExposed);

            List<ModelCatalogItem> catalog = agent.getDownloadManager().listCatalog();
            assertTrue("verified local market catalog is empty", catalog != null && !catalog.isEmpty());
            for (ModelCatalogItem item : catalog) {
                assertTrue("market item id missing", item.catalogModelId != null
                        && !item.catalogModelId.isEmpty());
                assertTrue("market item has invalid size", item.sizeBytes > 0L);
            }

            List<ModelDownloadInfo> voiceModels = agent.getVoiceManager().listOfflineModels();
            assertEquals("expected bundled Chinese and English speech models", 2, voiceModels.size());
            for (ModelDownloadInfo model : voiceModels) {
                assertEquals("offline voice model is not installed: " + model.modelId,
                        ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED, model.state);
                assertTrue("offline voice model has no verified bytes: " + model.modelId,
                        model.bytesDownloaded > 0L && model.bytesTotal > 0L
                                && model.bytesDownloaded == model.bytesTotal);
            }
        } finally {
            agent.release();
        }
    }

    /**
     * 真机闭环：不只验证 Binder 可获取，而是实际完成 PTT 的 Runtime 装配、AudioRecord 启动、
     * LISTENING 回调与显式停止。音频始终留在 Host 内，本测试不读取或保存识别文本。
     */
    @Test public void trustedClientPttStartsCaptureThenStopsCleanly() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());
            VoiceManager voice = agent.getVoiceManager();
            assertNotNull(voice);
            CountDownLatch listening = new CountDownLatch(1);
            CountDownLatch idle = new CountDownLatch(1);
            VoiceSessionHandle handle = voice.startUserInitiatedSession(
                    new VoiceSessionRequest(VoiceSessionRequest.TRIGGER_PTT,
                            Locale.getDefault().toLanguageTag()),
                    UUID.randomUUID().toString(), new VoiceSessionListener() {
                        @Override public void onSessionStateChanged(String sessionId, int state) {
                            if (state == VoiceServiceStatus.SESSION_LISTENING) listening.countDown();
                            if (state == VoiceServiceStatus.SESSION_IDLE) idle.countDown();
                        }
                        @Override public void onPartialText(String sessionId, String text) { }
                        @Override public void onFinalText(String sessionId, String text) { }
                        @Override public void onSessionError(String sessionId, int errorCode) { }
                    });
            assertNotNull("PTT admission failed", handle);
            assertNotNull("PTT session id missing", handle.sessionId);
            assertTrue("Audio capture did not reach LISTENING",
                    listening.await(15, TimeUnit.SECONDS));
            assertEquals(MatrixErrorCode.SUCCESS, voice.stopSession(handle.sessionId,
                    UUID.randomUUID().toString()).code);
            assertTrue("PTT stop did not converge to IDLE", idle.await(5, TimeUnit.SECONDS));
        } finally {
            agent.release();
        }
    }

    /**
     * 真机回归：唤醒后不说话是正常路径。必须在策略窗口后结束 PTT，并让 Host 回到可重新唤醒的 IDLE，
     * 不能残留在 LISTENING 把后续 PCM 错送给已经关闭的 ASR。
     */
    @Test public void trustedClientPttNoSpeechTimeoutReturnsIdle() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());
            VoiceManager voice = agent.getVoiceManager();
            assertNotNull(voice);
            CountDownLatch listening = new CountDownLatch(1);
            CountDownLatch idle = new CountDownLatch(1);
            VoiceSessionHandle handle = voice.startUserInitiatedSession(
                    new VoiceSessionRequest(VoiceSessionRequest.TRIGGER_PTT,
                            Locale.getDefault().toLanguageTag()),
                    UUID.randomUUID().toString(), new VoiceSessionListener() {
                        @Override public void onSessionStateChanged(String sessionId, int state) {
                            if (state == VoiceServiceStatus.SESSION_LISTENING) listening.countDown();
                            if (state == VoiceServiceStatus.SESSION_IDLE) idle.countDown();
                        }
                        @Override public void onPartialText(String sessionId, String text) { }
                        @Override public void onFinalText(String sessionId, String text) { }
                        @Override public void onSessionError(String sessionId, int errorCode) { }
                    });
            assertNotNull("PTT admission failed", handle);
            assertTrue("Audio capture did not reach LISTENING", listening.await(15, TimeUnit.SECONDS));
            assertTrue("No-speech timeout did not return PTT to IDLE", idle.await(7, TimeUnit.SECONDS));
            // CANCELLED 是内部清理过渡态，也会映射为回调 IDLE；等待真正的 RESET 收尾清空 sessionId，
            // 避免在 callback 与 Controller 串行队列之间的极小窗口读取到旧 session 快照。
            VoiceServiceStatus settled = awaitSessionCleared(voice, 2_000L);
            assertNotNull("No-speech cleanup did not settle a public status", settled);
            assertEquals("No-speech cleanup must leave public voice service idle",
                    VoiceServiceStatus.SESSION_IDLE, settled.sessionState);
            assertTrue("No-speech cleanup must clear the PTT session id", settled.currentSessionId == null);
        } finally {
            agent.release();
        }
    }

    private static VoiceServiceStatus awaitSessionCleared(VoiceManager voice, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        VoiceServiceStatus latest = null;
        do {
            latest = voice.getStatus();
            if (latest != null && latest.currentSessionId == null
                    && latest.sessionState == VoiceServiceStatus.SESSION_IDLE) return latest;
            SystemClock.sleep(50L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return latest;
    }
}
