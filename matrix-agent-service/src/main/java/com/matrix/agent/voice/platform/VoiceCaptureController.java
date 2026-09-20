package com.matrix.agent.voice.platform;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

import com.matrix.agent.voice.port.AsrPort;
import com.matrix.agent.voice.BargeInDetector;
import com.matrix.agent.voice.port.VoiceCapturePort;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.port.WakeWordPort;
import com.matrix.agent.voice.VoiceSessionController;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

/**
 * 音频采集控制器。
 *
 * <p>按 {@link VoiceSessionController#currentState()} 分发 PCM:IDLE→wakePort,LISTENING/BARGE_IN→asrPort,
 * SPEAKING→{@link BargeInDetector#feed}(RMS 连续帧判定,AEC 不可用时半双工),其它态不喂。
 *
 * <p><b>会话隔离 + 无锁并发</b>:每次 {@link #start()} 创建独立 {@link Session}(独占 AudioRecord/AEC/
 * 线程/running),{@link #current} 是 {@link AtomicReference}。并发控制全靠 CAS,无 synchronized:
 * start 用 {@code compareAndSet(null, s)} 抢创建权;stop/captureLoop finally 用 {@code compareAndSet(s, null)}
 * 清场。stop 超时(保留 current 所有权不清,防超时期间 start 建第二个 AudioRecord)后,旧线程 finally 释放旧 session 资源,互不影响。
 *
 * <p>barge-in 抽到 {@link BargeInDetector}(纯 Java,JVM 可测);AEC 是否启用经
 * {@link BargeInDetector#setAecAvailable} 传给 detector(AEC 不可用→半双工不触发);状态变化时
 * {@link BargeInDetector#reset} 防跨 SPEAKING 残留。阈值/连续帧数从 {@link VoicePolicyConfig}。
 *
 * <p>PCM 约定:16kHz、单声道、16bit LE。
 */
public final class VoiceCaptureController implements VoiceCapturePort {
    private static final String TAG = "MatrixAgent";
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_BYTES = 1600; // 50ms @ 16kHz mono 16bit
    /**
     * ASR 优先使用未针对通话优化的识别音源。部分设备对 VOICE_COMMUNICATION 施加强 AEC/降噪/增益，
     * 会损害离线识别；若 ROM 不支持则退回通用 MIC，而不是静默沿用通话链路。
     */
    private static final int[] ASR_AUDIO_SOURCES = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
    };

    private final VoiceSessionController controller;
    private final WakeWordPort wakePort;
    private final AsrPort asrPort;
    private final BargeInDetector detector;
    private final Context context;
    private final ThreadFactory captureThreadFactory;

    /** 当前会话(CAS 更新:start null→s;stop/finally s→null)。 */
    private final AtomicReference<Session> current = new AtomicReference<>();
    /** start 占位,防并发建双 AudioRecord(一个 CAS 成功才建,另一个直接返回 false)。 */
    private final AtomicBoolean starting = new AtomicBoolean();
    /** 会话 id 序列:每次 start 递增,captureLoop finally 通知携带,Runtime 校验防旧会话。 */
    private final java.util.concurrent.atomic.AtomicLong sessionSeq = new java.util.concurrent.atomic.AtomicLong();
    /** 预滚缓冲（阶段 C-3）；null 时跳过 append。 */
    private volatile com.matrix.agent.voice.AudioPrerollBuffer prerollBuffer;

    /** 采音线程退出时通知 Runtime(sid + reason);正常 stop=STOPPED,错误=精确 CAPTURE_* 码。
     * 采音线程不直连 Controller 报错——Runtime 校验 sid 后才转交(防迟到错误影响恢复后的新会话)。
     * 定义在 {@link VoiceCapturePort}(core),Runtime/测试依赖接口不依赖本类。 */
    private volatile VoiceCapturePort.TerminationListener terminationListener;
    @Override
    public void setTerminationListener(VoiceCapturePort.TerminationListener l) { this.terminationListener = l; }

    public VoiceCaptureController(VoiceSessionController controller, WakeWordPort wakePort,
            AsrPort asrPort, VoicePolicyConfig policy, Context context) {
        this(controller, wakePort, asrPort, policy, context, runnable -> {
            Thread thread = new Thread(runnable, "voice-capture-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Production Host path supplies a registry-owned factory for the single real-time loop. */
    public VoiceCaptureController(VoiceSessionController controller, WakeWordPort wakePort,
            AsrPort asrPort, VoicePolicyConfig policy, Context context,
            ThreadFactory captureThreadFactory) {
        if (controller == null) throw new IllegalArgumentException("controller 不能为空");
        if (wakePort == null) throw new IllegalArgumentException("wakePort 不能为空");
        if (asrPort == null) throw new IllegalArgumentException("asrPort 不能为空");
        if (policy == null) throw new IllegalArgumentException("policy 不能为空");
        if (context == null) throw new IllegalArgumentException("context 不能为空");
        if (captureThreadFactory == null) throw new IllegalArgumentException("captureThreadFactory 不能为空");
        this.controller = controller;
        this.wakePort = wakePort;
        this.asrPort = asrPort;
        this.detector = new BargeInDetector(policy, controller::onBargeIn);
        this.context = context.getApplicationContext();
        this.captureThreadFactory = captureThreadFactory;
    }

    /**
     * 开始采音。CAS 抢创建权:双 start 只有一个赢,另一个释放自己刚建的资源、返回。
     * 需先授予 RECORD_AUDIO。
     */
    @Override
    public long start() {
        if (current.get() != null) {
            Log.w(TAG, "[VoiceCapture] start rejected reason=existing_session");
            return -1; // 快速路径:已有会话(含 STOPPING 残留)
        }
        if (!starting.compareAndSet(false, true)) {
            Log.w(TAG, "[VoiceCapture] start rejected reason=start_in_flight");
            return -1; // #2:占位防并发建双 AudioRecord
        }
        AcousticEchoCanceler aec = null;
        AudioRecord ar = null;
        Session s = null;
        try {
            // lint 门禁——内部 checkSelfPermission 让 lint 认可权限已检查(不报 MissingPermission)
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "[VoiceCapture] permission check granted=false");
                throw new SecurityException("RECORD_AUDIO 未授予,无法采音");
            }
            // microphone FGS 前置（设计文档 §7.3）：采音 lease 获取 → startForeground
            if (!VoiceCaptureForegroundService.acquire(context, "ptt")) {
                throw new SecurityException("RECORD_AUDIO 前台服务启动被拒,无法采音");
            }
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, FRAME_BYTES * 4);
            Log.i(TAG, "[VoiceCapture] start config permissionGranted=true rate=" + SAMPLE_RATE
                    + " minBuffer=" + minBuf + " buffer=" + bufSize
                    + " sources=VOICE_RECOGNITION,MIC");
            if (minBuf <= 0) {
                throw new IllegalStateException("AudioRecord min buffer invalid: " + minBuf);
            }
            try {
                // 权限可能被运行时即时撤销,AudioRecord 构造抛 SecurityException。
                ar = createAsrAudioRecord(bufSize);
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(ar.getAudioSessionId());
                    if (aec != null) {
                        // create 成功 ≠ enable 成功,必须检查 setEnabled 返回值 + getEnabled
                        int r = aec.setEnabled(true);
                        boolean aecEnabled = (r == android.media.audiofx.AudioEffect.SUCCESS)
                                && aec.getEnabled();
                        if (aecEnabled) {
                            Log.i(TAG, "[Voice] 已启用 AEC(回声消除)");
                        } else {
                            Log.w(TAG, "[Voice] AEC enable 失败(code=" + r + "),降级半双工");
                            aec.release();
                            aec = null;
                        }
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "[Voice] RECORD_AUDIO 被撤销,无法采音: " + e.getMessage());
                throw e;
            }
            Log.i(TAG, "[VoiceCapture] AudioRecord initialized audioSession=" + ar.getAudioSessionId());
            boolean aecEnabled = aec != null && aec.getEnabled();
            // AEC 真正启用才全双工;否则半双工(SPEAKING 期间不开放免唤醒打断)
            detector.setAecAvailable(aecEnabled);
            ar.startRecording();
            Log.i(TAG, "[VoiceCapture] AudioRecord start state=" + ar.getRecordingState()
                    + " aecEnabled=" + aecEnabled);
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not enter recording state");
            }
            long sid = sessionSeq.incrementAndGet();
            final Session session = new Session(ar, aec, sid, context);
            s = session;
            Thread t = captureThreadFactory.newThread(() -> captureLoop(session));
            if (t == null) throw new IllegalStateException("captureThreadFactory returned null");
            session.thread = t;
            if (!current.compareAndSet(null, session)) {
                session.release(); // 另一个 start 已占 current(starting 已防,双重保险),释放避免泄漏。
                return -1;
            }
            t.start();
            Log.i(TAG, "[Voice] 采音启动(sid=" + sid + ")");
            return sid;
        } catch (RuntimeException e) {
            Log.e(TAG, "[VoiceCapture] start failed type=" + e.getClass().getSimpleName(), e);
            if (s != null) {
                current.compareAndSet(s, null);
                s.release();
            } else {
                releaseUnpublished(ar, aec);
            }
            throw e;
        } finally {
            starting.set(false);
        }
    }

    /** 依次尝试识别音源和 MIC；失败的候选立即释放，成功者由调用方接管。 */
    private AudioRecord createAsrAudioRecord(int bufferSize) {
        // 权限可能被运行时即时撤销；构造前在同一方法内显式检查（lint MissingPermission 契约）
        if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RECORD_AUDIO 未授予,无法采音");
        }
        for (int source : ASR_AUDIO_SOURCES) {
            AudioRecord candidate = new AudioRecord(source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "[VoiceCapture] AudioRecord source=" + audioSourceName(source));
                return candidate;
            }
            Log.w(TAG, "[VoiceCapture] AudioRecord source=" + audioSourceName(source)
                    + " state=uninitialized, trying fallback");
            candidate.release();
        }
        throw new IllegalStateException("AudioRecord 初始化失败(VOICE_RECOGNITION 与 MIC 均不可用)");
    }

    private static String audioSourceName(int source) {
        if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
        if (source == MediaRecorder.AudioSource.MIC) return "MIC";
        return "UNKNOWN(" + source + ")";
    }

    /** 回收尚未包装为 Session 的启动期资源。 */
    private static void releaseUnpublished(AudioRecord ar, AcousticEchoCanceler aec) {
        if (aec != null) {
            try { aec.release(); } catch (Exception e) { Log.w(TAG, "[Voice] AEC.release: " + e.getMessage()); }
        }
        // P3: 权限撤销等早期失败时 AudioRecord 尚未创建,判空防伪错误日志(异常被吞不遮蔽原异常,但日志误导)
        if (ar != null) {
            try { ar.release(); } catch (Exception e) { Log.w(TAG, "[Voice] AudioRecord.release: " + e.getMessage()); }
        }
    }

    /** read 返回值分类:负错误码(且未在停止中)为设备错误码,否则 null(继续/正常停止)。 */
    static String classifyReadError(int n, boolean running) {
        return decideFrameAction(n, running) == FrameAction.ERROR ? "CAPTURE_READ_ERROR_" + n : null;
    }

    /** 单帧 read 结果的处置。 */
    enum FrameAction { DISPATCH, SKIP, STOP, ERROR }

    /** 单帧决策:stop() 已请求(running=false)时一律 STOP——正帧也不分发(端口可能已并发 stop,P2);
     * 空读 SKIP;负错误码 ERROR;正常正帧 DISPATCH。 */
    static FrameAction decideFrameAction(int n, boolean running) {
        if (!running) return FrameAction.STOP;
        if (n == 0) return FrameAction.SKIP;
        return n < 0 ? FrameAction.ERROR : FrameAction.DISPATCH;
    }

    /** 采音循环帧读取 seam(JVM 测试注入假实现;生产为 AudioRecord.read,SecurityException 透传)。 */
    interface FrameReader {
        int read(byte[] buffer);
    }

    /** 采音循环帧分发 seam(JVM 测试注入假实现;生产为 KWS/ASR/barge-in 端口路由,RuntimeException 透传)。 */
    interface FrameDispatcher {
        void dispatch(byte[] pcm, int n);
    }

    /**
     * 采音循环核心(纯决策,可 JVM 测试):取帧 → {@link #decideFrameAction} 处置 → DISPATCH 帧交分发。
     * 返回 null=正常 STOPPED;非 null=精确 CAPTURE_* 错误码(经 onTerminated 上送,Runtime 校验 sid 后转交)。
     *
     * <p>P2:read() 返回后立刻复查 running——stop() 恰好发生在 read 返回正帧之后时,该帧不再送入
     * KWS/ASR(此时 Controller 取消和端口 stop 可能已并发执行);停止中分发抛异常按 STOPPED,
     * 不计 CAPTURE_PIPELINE_ERROR(防正常 pause→resume 误入故障预算)。
     *
     * <p>P3:read() 抛出的非 SecurityException 运行时异常上报 CAPTURE_READ_FAILED——底层音频实现
     * 抛 RuntimeException 不可吞成 STOPPED(否则错误统计和自动恢复路径失真);停止竞态下仍归 STOPPED。
     */
    static String driveFrames(FrameReader reader, FrameDispatcher dispatcher, BooleanSupplier running) {
        byte[] pcm = new byte[FRAME_BYTES];
        while (running.getAsBoolean()) {
            final int n;
            try {
                n = reader.read(pcm);
            } catch (SecurityException e) {
                if (!running.getAsBoolean()) return null; // stop() 并发打断伴随的异常视为正常停止
                return "CAPTURE_PERMISSION_REVOKED";
            } catch (RuntimeException e) {
                if (!running.getAsBoolean()) return null; // 停止竞态仍归 STOPPED
                return "CAPTURE_READ_FAILED";
            }
            switch (decideFrameAction(n, running.getAsBoolean())) {
                case SKIP: continue; // 罕见空读,正常继续
                case STOP: return null; // stop() 打断(负码/stop 后正帧)→ 正常停止
                case ERROR:
                    // 负错误码(ERROR_INVALID_OPERATION/BAD_VALUE/DEAD_OBJECT 等)不可恢复——
                    // 上送精确码退出,杜绝连续负码造成 CPU 忙循环 + Runtime 仍显示就绪却听不到声。
                    return classifyReadError(n, running.getAsBoolean());
                case DISPATCH:
                    break;
            }
            try {
                dispatcher.dispatch(pcm, n);
            } catch (RuntimeException e) {
                if (!running.getAsBoolean()) return null; // P2: 停止中的端口异常按 STOPPED,不计 pipeline error
                return "CAPTURE_PIPELINE_ERROR";
            }
        }
        return null;
    }

    private void captureLoop(Session s) {
        // 精确错误码(null=正常 STOPPED);采音线程不直连 Controller 报错,只经 onTerminated(sid, reason) 上送,
        // Runtime 校验 sid 后才转交 Controller——防迟到的错误任务影响 pause→resume 恢复后的新会话。
        String errorReason = null;
        // detector reset 的跨帧状态(状态变化清零)用数组闭包保持 loop 局部,不放实例字段(多会话隔离)。
        final VoiceSessionState.State[] prev = {null};
        // 读/分发处抛出的异常暂存:不在抛出点预判打日志(停止竞态下会被归类 STOPPED,打了就是误导);
        // 仅当 driveFrames 最终归类为错误时随终止码统一打(保留堆栈)(P3)。
        final RuntimeException[] thrown = {null};
        try {
            errorReason = driveFrames(
                    buf -> {
                        try {
                            return s.audioRecord.read(buf, 0, buf.length);
                        } catch (RuntimeException e) { // 含 SecurityException:记录后透传,分类交给 driveFrames
                            thrown[0] = e;
                            throw e;
                        }
                    },
                    (pcm, n) -> {
                        // 预滚 append（阶段 C-3）：每帧都进环形缓冲，不论状态
                        com.matrix.agent.voice.AudioPrerollBuffer preroll = prerollBuffer;
                        if (preroll != null) {
                            preroll.append(pcm, n);
                        }
                        VoiceSessionState.State state = controller.currentState();
                        if (state != prev[0]) {
                            detector.reset(); // 状态变化清零,防跨 SPEAKING 残留 loudFrames 误触发
                            prev[0] = state;
                        }
                        try {
                            switch (state) {
                                case IDLE:
                                    wakePort.feed(pcm, n);
                                    break;
                                case LISTENING:
                                case BARGE_IN_LISTENING:
                                    asrPort.feed(pcm, n);
                                    break;
                                case SPEAKING:
                                    detector.feed(pcm, n); // RMS 连续帧判定;AEC 不可用/forceHalfDuplex 时内部半双工
                                    break;
                                default:
                                    // WAKE_ACCEPTED / ENDPOINTING / RECOGNIZING / THINKING / CONFIRMING / CANCELLED / ERROR_ANNOUNCING
                                    break;
                            }
                        } catch (RuntimeException e) {
                            thrown[0] = e; // 同上:记录后透传,分类交给 driveFrames
                            throw e;
                        }
                    },
                    () -> s.running);
            if (errorReason != null) {
                RuntimeException t = thrown[0];
                if (t != null) Log.e(TAG, "[Voice] 采音错误(" + errorReason + "),停止采音", t);
                else Log.e(TAG, "[Voice] 采音错误(" + errorReason + "),停止采音");
            }
        } finally {
            // 只释放本会话自己的资源;CAS 清 current(仅在还是自己时,不误清新会话)
            s.release();
            current.compareAndSet(s, null);
            Log.i(TAG, "[Voice] 采音线程退出(sid=" + s.sid + ")");
            // #3/#5:无论正常退出还是错误退出都通知 Runtime(带 sid + 精确 reason),Runtime 校验 sid 后转交 Controller 并重试/收尾。
            VoiceCapturePort.TerminationListener l = terminationListener;
            if (l != null) l.onTerminated(s.sid, errorReason != null ? errorReason : "STOPPED");
        }
    }

    /**
     * 停止采音。释放安全:线程已退出则 release;超时未退出则保留 current 所有权不清(防超时期间
     * start 建第二个 AudioRecord),由 {@link #captureLoop} 的 finally 在线程真正退出时 CAS 清
     * current 并释放**本会话**资源。全程 CAS,无锁。
     */
    @Override
    public void stop() {
        Session s = current.get();
        if (s == null) return;
        s.running = false;
        try {
            if (s.audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                s.audioRecord.stop(); // 促阻塞的 read() 返回
            }
        } catch (Exception e) {
            Log.w(TAG, "[Voice] AudioRecord.stop: " + e.getMessage());
        }
        Thread t = s.thread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                // 不清 current:保留所有权,防止 stop 超时期间 start() 建第二个 AudioRecord;
                // 旧线程 captureLoop finally 会 CAS 清 current。期间 start() 见 current 非空返回 false。
                Log.w(TAG, "[Voice] 采音线程未及时退出,保留所有权等 finally 释放");
                return;
            }
        }
        s.release(); // 线程已退出,stop 释放(幂等)
        current.compareAndSet(s, null);
        Log.i(TAG, "[Voice] 采音停止");
    }

    /** 单次采音会话:独占 AudioRecord/AEC/线程/running。captureLoop 持本对象,finally 只释放自己。 */
    private static final class Session {
        final AudioRecord audioRecord;
        final AcousticEchoCanceler aec;
        final long sid;
        final Context sessionContext; // FGS release 需要
        volatile Thread thread;
        volatile boolean running = true;
        private final AtomicBoolean released = new AtomicBoolean();

        Session(AudioRecord audioRecord, AcousticEchoCanceler aec, long sid,
                Context sessionContext) {
            this.audioRecord = audioRecord;
            this.aec = aec;
            this.sid = sid;
            this.sessionContext = sessionContext;
        }

        /** 幂等释放(AtomicBoolean CAS)。captureLoop finally 或 stop() 调用,只执行一次。 */
        void release() {
            if (!released.compareAndSet(false, true)) return;
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] AudioRecord.release: " + e.getMessage());
            }
            VoiceCaptureForegroundService.release(sessionContext, "ptt");
            if (aec != null) {
                try {
                    aec.release();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] AEC.release: " + e.getMessage());
                }
            }
        }
    }
}
