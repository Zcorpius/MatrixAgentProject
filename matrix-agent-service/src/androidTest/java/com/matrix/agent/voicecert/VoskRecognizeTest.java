package com.matrix.agent.voicecert;


import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.voice.ModelPathResolver;
import com.matrix.agent.voice.port.WakeWordPort;
import com.matrix.agent.voice.vosk.VoskAsrEngine;
import com.matrix.agent.voice.vosk.VoskWakeAdapter;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.vosk.Model;

import java.io.File;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Vosk 识别路径烟雾测试(voicecert 门禁:见 {@link VoiceCertification})。
 *
 * <p>验证 native 加载 → Model 加载 → Recognizer → feed PCM → close 整条路径在真机不崩。
 * 静音 PCM 断言<b>静音不唤醒</b>(误唤醒率信号);正向唤醒经
 * {@link #englishWake_positiveSample_firesWithStartEpoch} 用真实语音样本驱动——
 * 该用例能在真机暴露"epoch 写回缺失导致真实唤醒被丢弃"类 P0(静音用例只验证不误触发,发现不了)。
 *
 * <p><b>前置</b>:模型预置(应用内下载或 run-as 导入)见 {@link VoiceCertification} 类注释;
 * 模型未预置时 {@link Assume#assumeTrue} 自动 skip,缺失即失败的门禁见 {@link VoskModelProvisionedGateTest}。
 */
@RunWith(AndroidJUnit4.class)
public class VoskRecognizeTest {
    private static final File MODELS =
            new File(ApplicationProvider.getApplicationContext().getFilesDir(), "vosk-model");

    /** 100ms 静音 PCM(16kHz mono 16bit LE = 3200 bytes)。 */
    private static byte[] silencePcm() {
        return new byte[3200];
    }

    /** 优先用版本目录布局的 active 版本目录;无 active 时 fallback 平铺目录(兼容新旧预置)。 */
    private static File resolveModelDir(String lang) {
        File langDir = new File(MODELS, lang);
        File active = ModelPathResolver.activeDir(langDir);
        return active != null ? active : langDir;
    }

    @Test
    public void chineseAsrEngineSmoke() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        File cnDir = resolveModelDir("cn");
        Assume.assumeTrue("跳过:cn 模型未预置 " + cnDir, cnDir != null && cnDir.isDirectory());
        Model cnModel = new Model(cnDir.getAbsolutePath());
        VoskAsrEngine engine = new VoskAsrEngine(cnModel);
        engine.start();
        byte[] pcm = silencePcm();
        engine.feed(pcm, pcm.length);   // 静音,不崩即可
        engine.stop();
        cnModel.close();
        assertNotNull(cnModel);
    }

    @Test
    public void englishWakeAdapterSmoke_silenceDoesNotWake() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        File enDir = resolveModelDir("en");
        Assume.assumeTrue("跳过:en 模型未预置 " + enDir, enDir != null && enDir.isDirectory());
        Model enModel = new Model(enDir.getAbsolutePath());
        VoskWakeAdapter wake = new VoskWakeAdapter(enModel);
        final int[] wakeCount = {0};
        wake.setListener(new WakeWordPort.Listener() {
            @Override
            public void onWake(long epoch) {
                wakeCount[0]++;
            }

            @Override
            public void onError(String code, long epoch) {
            }
        });
        wake.start();
        byte[] pcm = silencePcm();
        for (int i = 0; i < 20; i++) {
            wake.feed(pcm, pcm.length); // 共 2s 静音:不崩、不误唤醒
        }
        wake.stop();
        enModel.close();
        assertEquals("静音 PCM 不应触发唤醒", 0, wakeCount[0]);
    }

    /**
     * 正向唤醒门禁:真实 "hey matrix" 语音样本驱动 onWake,且携带的 epoch 必须等于
     * {@code start()} 返回值(锁内写回缺失 → entryEpoch 恒旧值 → 回调被 Controller 丢弃,P0 类缺陷)。
     *
     * <p>认证模式下模型/样本缺失或为空一律 <b>assert 失败</b>(不 Assume 跳过)——否则认证任务
     * 无法防住 epoch 回归。样本已入库:{@code app/src/androidTest/assets/hey-matrix-16k-mono.pcm}
     * (16kHz/单声道/16bit LE,macOS say/Samantha 合成,来源与再生成命令见 assets/README.md)。
     * 仅保留首个 {@code enabled()} Assume 作常规任务隔离。
     */
    @Test
    public void englishWake_positiveSample_firesWithStartEpoch() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        File enDir = resolveModelDir("en");
        assertTrue("认证模式:en 模型必须预置(预置流程见 VoiceCertification):" + enDir,
                enDir != null && enDir.isDirectory());
        byte[] sample = readSamplePcm();
        assertNotNull("正向唤醒样本缺失(app/src/androidTest/assets/hey-matrix-16k-mono.pcm,"
                + "来源与再生成命令见 assets/README.md)——认证模式不得跳过", sample);
        assertTrue("正向唤醒样本为空", sample.length > 0);

        Model enModel = new Model(enDir.getAbsolutePath());
        VoskWakeAdapter wake = new VoskWakeAdapter(enModel);
        final long[] wakeEpoch = {-1};
        final int[] wakeCount = {0};
        wake.setListener(new WakeWordPort.Listener() {
            @Override
            public void onWake(long epoch) {
                wakeEpoch[0] = epoch;
                wakeCount[0]++;
            }

            @Override
            public void onError(String code, long epoch) {
            }
        });
        WakeWordPort.WakeStartResult r = wake.start();
        assertTrue("wake.start 应成功", r.success());
        // 前后垫静音,按 100ms 帧喂入(对齐真实采音节拍)
        byte[] silence = silencePcm();
        for (int i = 0; i < 10; i++) wake.feed(silence, silence.length);
        feedAll(wake, sample);
        for (int i = 0; i < 10; i++) wake.feed(silence, silence.length);
        wake.stop();
        enModel.close();
        assertTrue("真实样本应触发唤醒(未触发:检查样本清晰度/模型 grammar)", wakeCount[0] >= 1);
        assertEquals("唤醒必须携带 start() 返回的代次(否则 Controller 会当旧代次丢弃)",
                r.epoch, wakeEpoch[0]);
    }

    /** 读取 androidTest APK assets 内的正向唤醒样本(instrumentation context,非被测应用 context);
     * 不存在返回 null。手动读流——InputStream.readAllBytes 是 API 33+,minSdk 28 不可用。 */
    private static byte[] readSamplePcm() {
        try (InputStream in = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getContext().getAssets().open("hey-matrix-16k-mono.pcm")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 100ms 帧喂入样本(16kHz mono 16bit = 3200 bytes/帧;feed 从数组头读,分段需切片)。 */
    private static void feedAll(VoskWakeAdapter wake, byte[] sample) {
        final int frame = 3200;
        for (int off = 0; off < sample.length; off += frame) {
            int len = Math.min(frame, sample.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(sample, off, chunk, 0, len);
            wake.feed(chunk, len);
        }
    }
}
