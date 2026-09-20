package com.matrix.agent.voice.sherpa;

import android.util.Log;

import com.matrix.agent.ondevice.sherpa.SherpaKeywordSpotter;
import com.matrix.agent.ondevice.sherpa.SherpaModelFiles;
import com.matrix.agent.voice.port.WakeWordPort;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@link WakeWordPort} 的 Sherpa KWS 实现——VoskWakeAdapter（英文小模型 + grammar）的
 * 可插拔替换，唤醒词表与 Vosk 路径保持一致（"hi matrix" / "hey matrix"）。
 *
 * <p>关键词表为音素拼写（上游 utils.cc EncodeBase 契约）：token 须在模型 tokens.txt 内，
 * {@code @} 后为回显名（不能含空格）；音素取自包内 CMU 发音词典 en.phone 的拼写。
 * 首次启动若 keywords.txt 缺失则写入默认唤醒词表（用户数据区，可编辑替换）。</p>
 *
 * <p>KWS 模型未安装时 fail-closed：start() 返回 errorCode 不抛异常，
 * 手动唤醒/PTT 入口不受影响（与量产 DSP 唤醒缺失的降级语义一致）。</p>
 */
public final class SherpaWakeAdapter implements WakeWordPort {

    private static final String TAG = "MatrixAgent";
    private static final String ERR_START = "SHERPA_WAKE_UNAVAILABLE";
    private static final String KEYWORDS_FILE = "keywords.txt";

    /** 触发阈值/加分：0.35 偏保守（低误唤醒），漏唤醒优先靠双唤醒词覆盖。 */
    private static final float KEYWORDS_THRESHOLD = 0.35f;
    private static final float KEYWORDS_SCORE = 1.5f;
    private static final int NUM_TRAILING_BLANKS = 2;
    private static final int NUM_THREADS = 1; // 3M 小模型单线程足够，省大核给 ASR

    /** 默认唤醒词表（音素拼写自包内 en.phone：HI/HEY/MATRIX 词条）。 */
    private static final String DEFAULT_KEYWORDS =
            "HH AY1 M EY1 T R IH0 K S @hi-matrix\n"
                    + "HH EY1 M EY1 T R IH0 K S @hey-matrix\n";

    private final Object lifecycleLock = new Object();
    private final File kwsModelDir;
    private final SherpaModelSpec spec;
    private Listener listener;
    private volatile long epoch;
    private SherpaKeywordSpotter spotter; // lifecycleLock 保护

    public SherpaWakeAdapter(File kwsModelDir, SherpaModelSpec spec) {
        this.kwsModelDir = kwsModelDir;
        this.spec = spec;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public WakeStartResult start() {
        synchronized (lifecycleLock) {
            if (spotter == null) {
                try {
                    spotter = createSpotterLocked();
                } catch (Exception e) {
                    Log.w(TAG, "[SherpaWake] KWS 不可用（模型未安装或加载失败）: "
                            + e.getMessage());
                    return new WakeStartResult(epoch, ERR_START);
                }
            }
        }
        final long sid;
        try {
            sid = spotter.start();
        } catch (RuntimeException e) {
            // 引擎异常不得穿透状态线程（会 FATAL 杀 Host）——降级为错误码，
            // 由 Controller 的有限退避重建兜底（WakeStartResult 错误通道的既定语义）
            Log.e(TAG, "[SherpaWake] KWS 启动异常，降级为不可用", e);
            return new WakeStartResult(epoch, ERR_START);
        }
        if (sid < 0) return new WakeStartResult(epoch, ERR_START);
        this.epoch = sid; // 端口代次 = 引擎会话代次（1:1 委托）
        return new WakeStartResult(sid, null);
    }

    @Override
    public void feed(byte[] pcm, int len) {
        SherpaKeywordSpotter s = spotter;
        if (s == null) return; // 未启动（模型未安装）：noop，采音链路无感
        s.feed(pcm, len);
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            if (spotter != null) spotter.stop(); // 保留引擎实例：下次 start 免重载
            epoch++;
        }
    }

    /** 释放 KWS 引擎（装配 close 时调用；WakeWordPort 契约外的方法）。 */
    public void close() {
        synchronized (lifecycleLock) {
            if (spotter != null) {
                spotter.close();
                spotter = null;
            }
        }
    }

    /** KWS 模型是否已安装（安装 UI 状态投影用；marker 取自 spec）。 */
    public static boolean modelInstalled(File kwsModelDir, SherpaModelSpec spec) {
        return kwsModelDir != null && kwsModelDir.isDirectory()
                && new File(kwsModelDir, spec.marker).isFile();
    }

    private SherpaKeywordSpotter createSpotterLocked() throws IOException {
        if (!modelInstalled(kwsModelDir, spec)) {
            throw new IOException("KWS_MODEL_MISSING: " + kwsModelDir);
        }
        File keywords = ensureDefaultKeywords();
        SherpaModelFiles files = new SherpaModelFiles(
                new File(kwsModelDir, spec.encoderName()),
                new File(kwsModelDir, spec.decoderName()),
                new File(kwsModelDir, spec.joinerName()),
                new File(kwsModelDir, spec.tokensName()));
        SherpaKeywordSpotter created = new SherpaKeywordSpotter(
                files, keywords.getAbsolutePath(), KEYWORDS_THRESHOLD, KEYWORDS_SCORE,
                NUM_TRAILING_BLANKS, NUM_THREADS);
        created.addListener((keyword, sessionId) -> {
            Listener l = listener;
            // 引擎已校验自身代次；此处再对齐端口代次（stop 后 spotter 未重建的空窗）
            if (l != null && sessionId == epoch) l.onWake(sessionId);
        });
        return created;
    }

    /** keywords.txt 缺失时写默认唤醒词表；已存在（用户自定义）则不动。 */
    private File ensureDefaultKeywords() throws IOException {
        File keywords = new File(kwsModelDir, KEYWORDS_FILE);
        if (keywords.isFile()) return keywords;
        try (FileOutputStream out = new FileOutputStream(keywords)) {
            out.write(DEFAULT_KEYWORDS.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "[SherpaWake] 写入默认唤醒词表: " + keywords);
        return keywords;
    }
}
