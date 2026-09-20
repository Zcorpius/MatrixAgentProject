package com.matrix.agent.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.client.MatrixAgent;
import com.matrix.agent.client.VoiceManager;
import com.matrix.agent.client.VoiceSessionListener;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sherpa 引擎真机闭环认证（阶段 D-验证）。
 *
 * <p>前置（设备夹具）：三件套模型已预置（adb 直推 files/sherpa-model 布局），
 * 引擎偏好已切至 SHERPA（语音页或 setAsrEngine）。本用例直接走 Binder PTT 全链路：
 * 懒重建 → Sherpa 装配（182MB int8 encoder + Silero VAD + KWS）→ LISTENING →
 * 静音收敛。触摸注入在 grus/LineageOS 22.2 上不可靠，故以 instrumented 通路替代 UI 驱动。</p>
 */
@RunWith(AndroidJUnit4.class)
public final class SherpaPttInstrumentedTest {

    /** 182MB int8 模型冷加载 + 采音启动 + 静音收敛的设备级预算。 */
    private static final long ASSEMBLY_BUDGET_MS = 60_000L;

    @Test public void sherpaPttSessionAssemblesAndConverges() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());
            VoiceManager voice = agent.getVoiceManager();
            assertNotNull(voice);

            // 1) 引擎偏好：SHERPA 已持久化（语音页切换或本用例补切）
            String engine = voice.getAsrEngine();
            if (!"SHERPA".equals(engine)) {
                VoiceOperationResult switched = voice.setAsrEngine("SHERPA",
                        UUID.randomUUID().toString());
                assertEquals(MatrixErrorCode.SUCCESS, switched.code);
                assertEquals("SHERPA", voice.getAsrEngine());
            }

            // 2) ASR/VAD/KWS + 应用内 Piper 播报模型均已安装（adb 旁路预置 → isDownloaded 命中）
            List<ModelDownloadInfo> models = voice.listOfflineModels();
            assertEquals(4, models.size());
            for (ModelDownloadInfo model : models) {
                assertEquals("模型未就绪: " + model.modelId,
                        ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED, model.state);
            }

            // 3) PTT：引擎热切换/冷启动后允许懒重建（不再 SERVICE_NOT_READY）
            LinkedBlockingQueue<Integer> states = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<String> finals = new LinkedBlockingQueue<>();
            AtomicReference<Integer> sessionError = new AtomicReference<>();
            CountDownLatch listening = new CountDownLatch(1);
            CountDownLatch terminal = new CountDownLatch(1);
            VoiceSessionListener listener = new VoiceSessionListener() {
                @Override public void onSessionStateChanged(String sessionId, int state) {
                    states.add(state);
                    if (state == VoiceServiceStatus.SESSION_LISTENING) listening.countDown();
                    if (state == VoiceServiceStatus.SESSION_IDLE) terminal.countDown();
                }
                @Override public void onPartialText(String sessionId, String text) { }
                @Override public void onFinalText(String sessionId, String text) {
                    finals.add(text);
                }
                @Override public void onSessionError(String sessionId, int errorCode) {
                    sessionError.set(errorCode);
                    terminal.countDown();
                }
            };
            VoiceSessionHandle handle = voice.startUserInitiatedSession(
                    new VoiceSessionRequest(VoiceSessionRequest.TRIGGER_PTT, "zh-CN"),
                    UUID.randomUUID().toString(), listener);
            assertNotNull("PTT 被拒绝（懒重建失效？查看 [VoiceSession] 日志）", handle);
            assertEquals(VoiceServiceStatus.SESSION_LISTENING, handle.state);
            String sessionId = handle.sessionId;

            // 4) LISTENING = Sherpa 装配成功（encoder/VAD/KWS native 全链路加载）
            assertTrue("未在预算内进入 LISTENING（装配超时/失败），states=" + states,
                    listening.await(ASSEMBLY_BUDGET_MS, TimeUnit.MILLISECONDS));

            // 5) 静音收敛：无人说话 → noSpeechTimeout/endpoint 空 final → 会话回 IDLE
            boolean converged = terminal.await(ASSEMBLY_BUDGET_MS, TimeUnit.MILLISECONDS);
            Integer error = sessionError.get();
            // 收敛允许两种形态：正常 IDLE，或策略性错误（如 reprompt 耗尽）——都证明
            // 引擎在真实处理音频流；断言排除的是装配/基础设施错误码。
            if (!converged) {
                voice.stopSession(sessionId, UUID.randomUUID().toString());
                throw new AssertionError("静音期未收敛，states=" + states
                        + " error=" + error);
            }
            assertTrue("基础设施错误码: " + error, error == null
                    || error == MatrixErrorCode.SUCCESS
                    || error == MatrixErrorCode.TASK_FAILED);
        } finally {
            agent.release();
        }
    }
}
