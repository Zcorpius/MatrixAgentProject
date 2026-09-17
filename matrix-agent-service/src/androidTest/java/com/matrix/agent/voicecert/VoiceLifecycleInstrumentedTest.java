package com.matrix.agent.voicecert;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.task.TaskState;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.platform.AndroidAudioFocusAdapter;
import com.matrix.agent.voice.platform.AndroidTtsAdapter;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Android 平台适配层(TTS / AudioFocus)仪器测试(voicecert 门禁:需真机,见
 * {@link VoiceCertification}——常规 connected 任务跳过,connectedVoiceCertificationAndroidTest 放行)。
 *
 * <p>TTS:初始化契约(onReady 或 onInitError 必达其一,不悬挂)、speak→onDone 真实播报、
 * shutdown 后迟到回调不上抛(listener 已清 + closed 拦截)。
 *
 * <p>AudioFocus:request 授权、被第三方(USAGE_MEDIA)抢占焦点 → onFocusLoss 通知、release 幂等。
 * 焦点抢占用同进程第二个 GAIN 请求模拟(设备焦点策略需支持,个别 ROM 行为可能不同)。
 */
@RunWith(AndroidJUnit4.class)
public class VoiceLifecycleInstrumentedTest {

    // ---- TTS ----

    @Test
    public void ttsInit_contract_readyOrInitError_neverHangs() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch settled = new CountDownLatch(1);
        final String[] result = {null};
        AndroidTtsAdapter tts = new AndroidTtsAdapter(context);
        tts.setListener(new com.matrix.agent.voice.port.TtsPort.Listener() {
            @Override public void onDone(String utteranceId) { }
            @Override public void onError(String utteranceId, String code) { }
            @Override public void onReady() { result[0] = "ready"; settled.countDown(); }
            @Override public void onInitError(String code) { result[0] = "initError:" + code; settled.countDown(); }
        });
        assertTrue("TTS 初始化必须回调 onReady/onInitError 之一(10s),不得悬挂",
                settled.await(10, TimeUnit.SECONDS));
        tts.shutdown();
    }

    @Test
    public void ttsSpeak_toDone_thenShutdown_lateCallbackNotDelivered() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AndroidTtsAdapter tts = new AndroidTtsAdapter(context);
        tts.setListener(new com.matrix.agent.voice.port.TtsPort.Listener() {
            @Override public void onDone(String utteranceId) { done.countDown(); }
            @Override public void onError(String utteranceId, String code) { done.countDown(); } // onError 也算收敛
            @Override public void onReady() { ready.countDown(); }
            @Override public void onInitError(String code) { }
        });
        assertTrue("TTS 引擎应在 10s 内就绪(设备需有中文 TTS 引擎)", ready.await(10, TimeUnit.SECONDS));
        tts.speak(new SpeakableResponse("已完成。", "zh-CN", TaskState.SUCCEEDED), "test_utterance_1");
        assertTrue("speak 后应收到 onDone/onError 收敛(15s)", done.await(15, TimeUnit.SECONDS));

        // shutdown 后迟到回调不上抛:listener 已清,引擎侧任何迟到事件不得再触达
        final AtomicInteger lateCallbacks = new AtomicInteger();
        tts.setListener(new com.matrix.agent.voice.port.TtsPort.Listener() {
            @Override public void onDone(String utteranceId) { lateCallbacks.incrementAndGet(); }
            @Override public void onError(String utteranceId, String code) { lateCallbacks.incrementAndGet(); }
            @Override public void onReady() { }
            @Override public void onInitError(String code) { }
        });
        tts.speak(new SpeakableResponse("第二句。", "zh-CN", TaskState.SUCCEEDED), "test_utterance_2");
        tts.shutdown(); // speak 后立即 shutdown:引擎侧迟到 onDone 必须被 closed 拦截
        Thread.sleep(1000); // 给迟到回调留窗口
        assertEquals("shutdown 后迟到回调不得上抛", 0, lateCallbacks.get());
    }

    // ---- AudioFocus ----

    @Test
    public void audioFocus_requestGranted_lossOnCompetingRequest_releaseIdempotent() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        Context context = ApplicationProvider.getApplicationContext();
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AndroidAudioFocusAdapter adapter = new AndroidAudioFocusAdapter(context);
        CountDownLatch loss = new CountDownLatch(1);
        adapter.setListener(loss::countDown);
        assertTrue("首次焦点请求应被授权", adapter.request());

        // 模拟第三方(USAGE_MEDIA)抢焦点 → 助手焦点应收到 loss 通知
        AudioFocusRequest competing = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .build();
        try {
            am.requestAudioFocus(competing);
            assertTrue("被 USAGE_MEDIA 抢占后应通知 onFocusLoss(5s)", loss.await(5, TimeUnit.SECONDS));
        } finally {
            am.abandonAudioFocusRequest(competing);
        }
        adapter.release();
        adapter.release(); // 幂等:不抛
    }
}
