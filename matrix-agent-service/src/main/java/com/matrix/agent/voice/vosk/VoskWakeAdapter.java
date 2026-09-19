package com.matrix.agent.voice.vosk;

import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.Locale;

/**
 * {@link com.matrix.agent.voice.port.WakeWordPort} 的 Vosk 实现。
 *
 * <p>用英文小模型 + grammar 受限词表做关键词检测:grammar 包含主唤醒词 "hi matrix"
 * 与兼容说法 "hey matrix"，
 * 命中唤醒词时触发 {@link com.matrix.agent.voice.port.WakeWordPort.Listener#onWake()}。
 * 同时检查 final 与 partial,降低响应延迟。
 *
 * <p><b>线程安全</b>:细粒度锁 {@link #recognizerLock} 只保护 recognizer 的 native 操作
 * (acceptWaveForm + 取 result)与 close;唤醒词匹配与回调在锁外,经代次(epoch)校验防旧
 * recognizer 的迟到唤醒污染 stop+start 重建后的新会话。
 *
 * <p>PCM 约定:16kHz、单声道、16bit little-endian。
 */
public final class VoskWakeAdapter implements com.matrix.agent.voice.port.WakeWordPort {
    private static final String TAG = "MatrixAgent";
    private static final String ERR_START = "VOSK_WAKE_START_FAILED";

    /** grammar JSON:主唤醒词、兼容说法 + [unk] 通配(其它声音不触发)。 */
    private static final String GRAMMAR = "[\"hi matrix\", \"hey matrix\", \"[unk]\"]";
    private static final String WAKE_PHRASE = "matrix";

    private final Model enModel;
    private final Object recognizerLock = new Object();
    private volatile Listener listener;
    private Recognizer recognizer; // recognizerLock 保护
    /** recognizer 代次:start 递增,stop 作废递增;volatile 供 feed 锁外派发前校验。 */
    private volatile long epoch;
    private long epochSeq; // recognizerLock 保护

    public VoskWakeAdapter(Model enModel) {
        if (enModel == null) throw new IllegalArgumentException("enModel 不能为空");
        this.enModel = enModel;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public WakeStartResult start() {
        try {
            synchronized (recognizerLock) {
                if (recognizer == null) {
                    recognizer = new Recognizer(enModel, VoskAsrEngine.SAMPLE_RATE, GRAMMAR);
                }
                // P0 修复:锁内同步写回 epoch(此前只返回 ++epochSeq 未写字段,feed 快照的
                // entryEpoch 恒为旧值,onWake 携带旧代次被 Controller 丢弃——真实唤醒永远进不了 LISTENING)
                epoch = ++epochSeq;
                return new WakeStartResult(epoch, null);
            }
        } catch (IOException e) {
            Log.e(TAG, "[Voice] VoskWake 启动失败: " + e.getClass().getSimpleName());
            return new WakeStartResult(epoch, ERR_START); // 失败由调用方检查 errorCode 退避重建(不触发同步 onError)
        }
    }

    @Override
    public void feed(byte[] pcm, int len) {
        if (pcm == null || len <= 0) return;
        String raw;
        boolean endpoint;
        final long entryEpoch;
        synchronized (recognizerLock) {
            if (recognizer == null) return;
            entryEpoch = epoch; // 与本次 native 识别同代次(锁内快照)
            endpoint = recognizer.acceptWaveForm(pcm, len);
            raw = endpoint ? recognizer.getResult() : recognizer.getPartialResult();
        }
        // 锁外:JSON 解析 + 唤醒词匹配 + 回调。
        // P2:解析期间可能发生 stop+start 重建(如采音错误清理),旧 recognizer 的迟到唤醒不得
        // 污染新会话——派发前校验代次未变;Controller 侧再校验 epoch == start() 返回值,双重丢弃。
        String text = parse(raw, endpoint ? "text" : "partial");
        if (isWakePhrase(text)) {
            if (entryEpoch != epoch) return; // 重建/停止已发生,丢弃旧结果
            Listener l = listener;
            if (l != null) l.onWake(entryEpoch);
        }
    }

    @Override
    public void stop() {
        synchronized (recognizerLock) {
            epoch = ++epochSeq; // 作废在途结果(迟到 onWake 的代次校验将失败)
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    /** 纯文本命中判断，保持包可见以便不依赖 Vosk native 的回归测试。 */
    static boolean isWakePhrase(String text) {
        if (text == null) return false;
        String normalized = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.equals("hi " + WAKE_PHRASE)
                || normalized.equals("hey " + WAKE_PHRASE);
    }

    private static String parse(String json, String field) {
        try {
            return new JSONObject(json).optString(field, "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
