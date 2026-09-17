package com.matrix.agent.voicecert;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.voice.port.AsrPort;
import com.matrix.agent.voice.port.AudioFocusPort;
import com.matrix.agent.voice.NoopVoiceMetrics;
import com.matrix.agent.voice.ResponsePresenter;
import com.matrix.agent.voice.port.TtsPort;
import com.matrix.agent.voice.port.VadPort;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.port.WakeWordPort;
import com.matrix.agent.voice.VoiceSessionController;
import com.matrix.agent.voice.platform.VoiceCaptureController;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 无 RECORD_AUDIO 权限 → 采音 start() fail-fast 抛 SecurityException(voicecert 门禁,
 * 见 {@link VoiceCertification})。
 *
 * <p><b>为什么不在测试内 revoke</b>:进程内 {@code revokeRuntimePermission} 会让系统直接杀掉
 * 被测进程,测试结果点经常来不及上报(竞态,门禁不可靠)。因此由门禁 task 在两次
 * instrumentation 调用<b>之间</b>于宿主机执行 {@code pm revoke}:本类在全新无权限进程里运行
 * (无 {@code GrantPermissionRule}),走与运行期撤销相同的
 * {@code checkSelfPermission → SecurityException} fail-fast 路径,进程全程存活、结果可靠上报。
 * 本类<b>不得</b>与带 {@code GrantPermissionRule} 的类在同一调用中运行(权限会被授予)。
 */
@RunWith(AndroidJUnit4.class)
public class VoicePermissionDeniedInstrumentedTest {

    /** 计数喂入字节的假 wake 端口(无权限时不得收到任何 PCM)。 */
    private static final class CountingWakePort implements WakeWordPort {
        final AtomicInteger fedBytes = new AtomicInteger();
        long epochSeq;
        @Override public void setListener(WakeWordPort.Listener listener) { }
        @Override public WakeStartResult start() { return new WakeStartResult(++epochSeq, null); }
        @Override public void feed(byte[] pcm, int len) { fedBytes.addAndGet(len); }
        @Override public void stop() { epochSeq++; }
    }

    private VoiceCaptureController newCapture(CountingWakePort wake) {
        Context context = ApplicationProvider.getApplicationContext();
        Executor direct = Runnable::run;
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "test-voice-timeout");
                    t.setDaemon(true);
                    return t;
                });
        VoiceSessionController controller = new VoiceSessionController(
                wake, new NoopAsrPort(), new NoopTtsPort(), new NoopVadPort(), new NoopFocusPort(),
                req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                new ResponsePresenter(), VoicePolicyConfig.defaults(), direct, direct, scheduler,
                NoopVoiceMetrics.INSTANCE);
        controller.start();
        return new VoiceCaptureController(controller, wake, new NoopAsrPort(),
                VoicePolicyConfig.defaults(), context);
    }

    private static final class NoopAsrPort implements AsrPort {
        @Override public void setListener(AsrPort.Listener listener) { }
        @Override public AsrStartResult start() { return new AsrStartResult(1, null); }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { }
        @Override public void finish() { }
    }

    private static final class NoopTtsPort implements TtsPort {
        @Override public void setListener(TtsPort.Listener listener) { }
        @Override public void speak(com.matrix.agent.voice.SpeakableResponse response, String utteranceId) { }
        @Override public void stop() { }
    }

    private static final class NoopVadPort implements VadPort {
        @Override public void setListener(VadPort.Listener listener) { }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void reset() { }
    }

    private static final class NoopFocusPort implements AudioFocusPort {
        @Override public void setListener(AudioFocusPort.Listener listener) { }
        @Override public boolean request() { return true; }
        @Override public void release() { }
    }

    @Test
    public void permissionDenied_startThrowsSecurityException() {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        CountingWakePort wake = new CountingWakePort();
        VoiceCaptureController capture = newCapture(wake);
        try {
            capture.start(); // 无权限必须 fail-fast 抛出,不静默死/不返回 busy/不喂 PCM
            fail("无 RECORD_AUDIO 权限,start() 应抛 SecurityException");
        } catch (SecurityException expected) {
            // 预期:权限类错误显式上抛(Runtime 据此提示用户,不盲目重试)
            assertTrue("无权限时 KWS 不应收到任何 PCM", wake.fedBytes.get() == 0);
        }
    }
}
