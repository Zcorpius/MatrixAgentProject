package com.matrix.agent.voice.sherpa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.ondevice.sherpa.SherpaPiperTtsEngine;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Native Piper certification: verifies model loading, non-silent PCM and Android's real assistant
 * audio route. The test belongs to the Host module because it deliberately exercises an internal
 * on-device engine; cross-APK clients must remain SDK-only.
 */
@RunWith(AndroidJUnit4.class)
public final class SherpaPiperTtsInstrumentedTest {
    @Test public void installedPiperGeneratesNonEmptyChinesePcm() {
        Assume.assumeTrue("需要 -e sherpaTtsCert true",
                "true".equals(InstrumentationRegistry.getArguments().getString("sherpaTtsCert")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "sherpa-model/tts-zh-xiaoya");
        File active = com.matrix.agent.voice.ModelPathResolver.activeDir(root);
        Assume.assumeTrue("Piper 尚未安装", active != null);
        try (SherpaPiperTtsEngine engine = new SherpaPiperTtsEngine(active)) {
            SherpaPiperTtsEngine.PcmAudio pcm = engine.generate("本地语音播报验证成功。");
            assertTrue("采样率异常: " + pcm.sampleRate(), pcm.sampleRate() >= 16_000);
            assertTrue("没有生成 PCM", pcm.samples().length > 4_000);
            boolean hasSignal = false;
            for (float sample : pcm.samples()) {
                if (Math.abs(sample) > 0.001f) { hasSignal = true; break; }
            }
            assertTrue("PCM 全静音", hasSignal);
            writeToAssistantRoute(pcm);
        }
    }

    private static void writeToAssistantRoute(SherpaPiperTtsEngine.PcmAudio pcm) {
        short[] samples = toPcm16(pcm.samples());
        int minimum = AudioTrack.getMinBufferSize(pcm.sampleRate(),
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        assertTrue("AudioTrack 缓冲区无效: " + minimum, minimum > 0);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(pcm.sampleRate())
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minimum, samples.length * Short.BYTES))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        try {
            assertTrue("AudioTrack 初始化失败，state=" + track.getState(),
                    track.getState() == AudioTrack.STATE_NO_STATIC_DATA
                            || track.getState() == AudioTrack.STATE_INITIALIZED);
            assertEquals("PCM 未完整写入播放设备", samples.length,
                    track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING));
            assertEquals("写入 PCM 后 AudioTrack 未就绪", AudioTrack.STATE_INITIALIZED,
                    track.getState());
            track.play();
            assertEquals("AudioTrack 未进入播放态", AudioTrack.PLAYSTATE_PLAYING,
                    track.getPlayState());
            SystemClock.sleep(Math.min(2_000L, Math.max(250L,
                    samples.length * 1_000L / pcm.sampleRate())));
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    private static short[] toPcm16(float[] source) {
        short[] result = new short[source.length];
        for (int i = 0; i < source.length; i++) {
            float sample = Math.max(-1f, Math.min(1f, source[i]));
            result[i] = (short) Math.round(sample * Short.MAX_VALUE);
        }
        return result;
    }
}
