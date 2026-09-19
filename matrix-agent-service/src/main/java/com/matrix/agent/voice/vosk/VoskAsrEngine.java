package com.matrix.agent.voice.vosk;

import android.util.Log;

import com.matrix.agent.voice.FinalTranscript;
import com.matrix.agent.voice.platform.VoskResultParser;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vosk ASR 共享内核。
 *
 * <p>封装中文自由识别 {@link Recognizer}，每帧 PCM 产出 partial / final / endpoint 三类回调。
 * {@link VoskAsrAdapter} 与 {@link VoskEndpointAdapter} 共享同一实例。
 *
 * <p><b>线程安全</b>:采音线程的 {@code acceptWaveForm} 与 Controller 线程的 {@code close} 必须互斥
 * (否则 native use-after-close 崩溃)。用细粒度锁 {@link #recognizerLock}——**只在 recognizer 的
 * native 操作**({@code acceptWaveForm} + 取 result)处持锁;JSON 解析、listeners 回调在锁外进行
 * (不碰 recognizer,无需互斥,且避免回调链拖长锁持有时间)。{@code start}/{@code reset}/{@code stop}
 * 共用同一把锁。listeners 用 {@link CopyOnWriteArrayList},增删无锁。
 *
 * <p>PCM 约定:16kHz、单声道、16bit little-endian。
 */
public final class VoskAsrEngine {
    private static final String TAG = "MatrixAgent";

    /** Vosk 采样率固定 16kHz。 */
    public static final float SAMPLE_RATE = 16000f;

    private final Model cnModel;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 只保护 recognizer 字段的 native 操作与 close,不覆盖 JSON/回调。 */
    private final Object recognizerLock = new Object();
    private Recognizer recognizer; // recognizerLock 保护
    /** 当前 ASR 轮送入 native 的 PCM 能量摘要；只留聚合数值，绝不记录音频或转写原文。 */
    private final PcmLevelSummary pcmLevels = new PcmLevelSummary();
    /**
     * 现场 ASR 排查用：只打印发生变化的 partial，避免 50ms 一帧的重复结果淹没日志。
     * 本字段及 reset 均由 recognizerLock 保护。
     */
    private String lastLoggedPartial = "";
    /** epoch:start/stop 各 ++,feed/finish 锁外校验丢弃 stop 后的延迟回调(防跨会话污染)。 */
    private volatile long epoch;

    public VoskAsrEngine(Model cnModel) {
        if (cnModel == null) throw new IllegalArgumentException("cnModel 不能为空");
        this.cnModel = cnModel;
    }

    /** engine 回调:partial / final / endpoint。AsrAdapter 与 EndpointAdapter 各注册一个。 */
    public interface Listener {
        void onPartial(String text, long sessionId);

        void onFinal(FinalTranscript transcript, long sessionId);

        void onEndpoint(long sessionId);
    }

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 创建自由识别 Recognizer。@return 当前 sessionId(epoch),回调校验防跨会话污染。 */
    public long start() throws IOException {
        synchronized (recognizerLock) {
            if (recognizer == null) {
                recognizer = new Recognizer(cnModel, SAMPLE_RATE);
                recognizer.setWords(true); // 启用 word result,供 VoskResultParser 解析 conf
                pcmLevels.reset();
                lastLoggedPartial = "";
                epoch++;
            }
            return epoch;
        }
    }

    /**
     * 喂一帧 PCM。锁内只做 recognizer 的 native 操作(acceptWaveForm + 取 result);
     * JSON 解析与 listeners 回调在锁外。
     */
    public void feed(byte[] pcm, int len) {
        if (pcm == null || len <= 0) return;
        String raw;
        boolean endpoint;
        final long myEpoch;
        PcmLevelSummary.Snapshot endpointAudio = null;
        synchronized (recognizerLock) {
            if (recognizer == null) return;
            myEpoch = epoch;
            pcmLevels.add(pcm, len);
            endpoint = recognizer.acceptWaveForm(pcm, len);
            raw = endpoint ? recognizer.getResult() : recognizer.getPartialResult();
            if (endpoint) endpointAudio = pcmLevels.takeAndReset();
        }
        if (epoch != myEpoch) return; // stop 已切 epoch,丢弃锁外延迟回调
        if (endpoint) {
            // JSON 解析(含 word conf 聚合)迁入纯工具 VoskResultParser,JVM 可测。
            VoskResultParser.ParsedResult parsed = VoskResultParser.parse(raw);
            logFinalDiagnostics("endpoint", parsed, endpointAudio, myEpoch, raw);
            for (Listener l : listeners) {
                l.onFinal(transcriptOf(parsed), myEpoch);
                l.onEndpoint(myEpoch);
            }
        } else {
            String partial = parsePartial(raw);
            if (!partial.isEmpty()) {
                logPartialIfChanged(partial, raw, myEpoch);
                for (Listener l : listeners) l.onPartial(partial, myEpoch);
            }
        }
    }

    /**
     * 强制产出当前 final(不触发 endpoint),供最大说话时长用。
     * 锁内取 getFinalResult(native,强制 flush 并重置内部状态),锁外解析 + 回调。
     * 注:不用 getResult——它返回当前累积快照且不重置,未自然 endpoint 时可能为空或为旧结果,
     * 最大说话时长强制结束时拿不到可靠 final。
     */
    public void finish() {
        String raw;
        final long myEpoch;
        PcmLevelSummary.Snapshot audio;
        synchronized (recognizerLock) {
            if (recognizer == null) return;
            myEpoch = epoch;
            raw = recognizer.getFinalResult();
            audio = pcmLevels.takeAndReset();
        }
        if (epoch != myEpoch) return;
        VoskResultParser.ParsedResult parsed = VoskResultParser.parse(raw);
        logFinalDiagnostics("forced_finish", parsed, audio, myEpoch, raw);
        for (Listener l : listeners) {
            l.onFinal(transcriptOf(parsed), myEpoch);
        }
    }

    /** 重置 Recognizer 状态(丢弃当前 partial)。 */
    public void reset() {
        synchronized (recognizerLock) {
            if (recognizer != null) recognizer.reset();
            pcmLevels.reset();
            lastLoggedPartial = "";
        }
    }

    /** 释放 Recognizer。与 feed 的 native 调用互斥,避免 use-after-close。 */
    public void stop() {
        synchronized (recognizerLock) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
                pcmLevels.reset();
                lastLoggedPartial = "";
                epoch++;
            }
        }
    }

    private static String parsePartial(String json) {
        try {
            return new JSONObject(json).optString("partial", "").trim();
        } catch (Exception e) {
            Log.w(TAG, "[Voice] Vosk partial JSON 解析失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 临时现场诊断：用户明确要求将 ASR 结果完整写入 logcat。
     * 这里不会记录原始 PCM；排查完成后应恢复为只记录结构化元数据的默认隐私策略。
     */
    private void logPartialIfChanged(String text, String rawJson, long sessionId) {
        synchronized (recognizerLock) {
            if (sessionId != epoch || text.equals(lastLoggedPartial)) return;
            lastLoggedPartial = text;
        }
        Log.i(TAG, "[Voice][ASR][DEBUG] partial sid=" + sessionId
                + " text=" + text + " rawJson=" + rawJson);
    }

    private static FinalTranscript transcriptOf(VoskResultParser.ParsedResult parsed) {
        return new FinalTranscript(parsed.text(), "zh-CN", parsed.confidence(),
                parsed.confidenceAvailable());
    }

    private static void logFinalDiagnostics(String origin, VoskResultParser.ParsedResult parsed,
            PcmLevelSummary.Snapshot audio, long sessionId, String rawJson) {
        String audioSummary = audio == null ? "unavailable" : audio.toLogString();
        Log.i(TAG, "[Voice][ASR][DEBUG] final origin=" + origin + " sid=" + sessionId
                + " text=" + parsed.text()
                + " textChars=" + parsed.text().length()
                + " resultWords=" + parsed.resultWordCount()
                + " confidenceWords=" + parsed.confidenceWordCount()
                + " confidenceAvailable=" + parsed.confidenceAvailable()
                + " confidence=" + parsed.confidence()
                + " confidenceSource=" + parsed.confidenceSource()
                + " pcm=" + audioSummary
                + " rawJson=" + rawJson);
    }

    /** PCM 电平的会话内聚合，所有访问由 {@link #recognizerLock} 保护。 */
    private static final class PcmLevelSummary {
        private long frameCount;
        private long sampleCount;
        private double sumSquares;
        private int peak;

        void add(byte[] pcm, int len) {
            int boundedLen = Math.min(len, pcm.length) & ~1;
            frameCount++;
            for (int i = 0; i < boundedLen; i += 2) {
                int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                int magnitude = Math.abs(sample == Short.MIN_VALUE ? Short.MAX_VALUE : sample);
                sumSquares += (double) sample * sample;
                sampleCount++;
                if (magnitude > peak) peak = magnitude;
            }
        }

        Snapshot takeAndReset() {
            Snapshot snapshot = new Snapshot(frameCount, sampleCount,
                    sampleCount == 0 ? 0 : (int) Math.sqrt(sumSquares / sampleCount), peak);
            reset();
            return snapshot;
        }

        void reset() {
            frameCount = 0;
            sampleCount = 0;
            sumSquares = 0;
            peak = 0;
        }

        static final class Snapshot {
            private final long frames;
            private final long samples;
            private final int rms;
            private final int peak;

            Snapshot(long frames, long samples, int rms, int peak) {
                this.frames = frames;
                this.samples = samples;
                this.rms = rms;
                this.peak = peak;
            }

            String toLogString() {
                return "frames=" + frames + ",samples=" + samples + ",rms=" + rms + ",peak=" + peak;
            }
        }
    }
}
