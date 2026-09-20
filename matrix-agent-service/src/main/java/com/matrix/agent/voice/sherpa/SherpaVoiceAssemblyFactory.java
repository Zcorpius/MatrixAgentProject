package com.matrix.agent.voice.sherpa;

import android.app.Application;
import android.util.Log;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.ondevice.sherpa.SherpaAsrEngine;
import com.matrix.agent.ondevice.sherpa.SherpaModelFiles;
import com.matrix.agent.ondevice.sherpa.SherpaSileroVad;
import com.matrix.agent.voice.ModelPathResolver;
import com.matrix.agent.voice.NoopVoiceMetrics;
import com.matrix.agent.voice.ResponsePresenter;
import com.matrix.agent.voice.VoiceAssembly;
import com.matrix.agent.voice.VoiceAssemblyFactory;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.VoiceSessionController;
import com.matrix.agent.voice.platform.AndroidAudioFocusAdapter;
import com.matrix.agent.voice.platform.VoiceCaptureController;
import com.matrix.agent.voice.port.ManagedTtsPort;

import java.io.File;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;

/**
 * Sherpa-NN 装配工厂——VoskVoiceAssemblyFactory 的可插拔替换（设计文档 §10 阶段 D）。
 *
 * <p>模型三件套（{@link SherpaModelSpec}）：ASR 必装（prepare fail-closed）；
 * VAD/KWS 可选增强——VAD 缺失降级为无门控全量喂识别器，KWS 缺失唤醒不可用
 * （手动/PTT 入口不受影响）。create 装配官方 Kotlin API 引擎（Operit 模式，
 * 不手写 C++ JNI）：Silero 门控 + endpoint rules 端点 + 音素级 KWS 唤醒。</p>
 *
 * <p>模型治理（下载→SHA-256→解压→版本目录→原子 promote）与 Vosk 同体系，
 * 由 {@link SherpaModelDownloader} 承担；安装/状态查询入口在
 * {@code host.rpc.VoiceServiceStub}（按引擎路由），本工厂只负责 prepare/create。</p>
 */
public final class SherpaVoiceAssemblyFactory implements VoiceAssemblyFactory {

    private static final String TAG = "MatrixAgent";

    /** ASR 推理线程：大核 ×2（与 MNN LLM 分时共存）。 */
    private static final int ASR_NUM_THREADS = 2;

    /** Silero 门控参数。minSilence 必须 > endpoint rule2（1.2s），保证端点先于关窗触发。 */
    private static final float VAD_THRESHOLD = 0.5f;
    private static final float VAD_MIN_SILENCE_SECONDS = 1.6f;
    private static final float VAD_MIN_SPEECH_SECONDS = 0.1f;
    private static final float VAD_MAX_SPEECH_SECONDS = 20.0f;

    private final Application app;
    private final SherpaModelDownloader downloader;
    private final SherpaModelSpec asrSpec;
    private final SherpaModelSpec vadSpec;
    private final SherpaModelSpec kwsSpec;
    private final VoiceTtsFactory ttsFactory;
    private final ThreadFactory captureThreadFactory;

    public SherpaVoiceAssemblyFactory(Application app, ModelDownloadDao dao,
            ThreadFactory captureThreadFactory, OkHttpClient httpClient) {
        this(app, dao, captureThreadFactory, httpClient, httpClient);
    }

    /** Keeps large model download policy separate from bounded cloud synthesis calls. */
    public SherpaVoiceAssemblyFactory(Application app, ModelDownloadDao dao,
            ThreadFactory captureThreadFactory, OkHttpClient downloadClient,
            OkHttpClient cloudClient) {
        this.app = app;
        File sherpaRoot = new File(app.getFilesDir(), "sherpa-model");
        this.asrSpec = SherpaModelSpec.streamingBilingual(sherpaRoot);
        this.vadSpec = SherpaModelSpec.sileroVad(sherpaRoot);
        this.kwsSpec = SherpaModelSpec.kwsZhEn(sherpaRoot);
        this.downloader = new SherpaModelDownloader(app, dao, downloadClient);
        this.ttsFactory = new VoiceTtsFactory(app, dao, downloadClient, cloudClient);
        this.captureThreadFactory = captureThreadFactory;
    }

    @Override
    public void prepare(ProgressListener progress, BooleanSupplier cancelled)
            throws Exception {
        if (downloader.isDownloaded(asrSpec)) {
            progress.onProgress("Sherpa ASR 模型就绪");
        } else {
            // ASR 模型不存在——fail-closed，不隐式下载 511MB（语音页显式安装）
            Log.i(TAG, "[SherpaAssembly] 可选增强 vad=" + downloader.isDownloaded(vadSpec)
                    + " kws=" + downloader.isDownloaded(kwsSpec));
            throw new IllegalStateException("Sherpa ASR 模型未安装: " + asrSpec.targetDir);
        }
        Log.i(TAG, "[SherpaAssembly] prepare ok vad=" + downloader.isDownloaded(vadSpec)
                + " kws=" + downloader.isDownloaded(kwsSpec));
    }

    @Override
    public VoiceAssembly create(AssemblyContext context) throws Exception {
        // P2：中途异常按依赖逆序释放已建资源（native 生命周期：stream → recognizer）
        SherpaSileroVad vad = null;
        SherpaAsrEngine engine = null;
        SherpaWakeAdapter wake = null;
        ManagedTtsPort tts = null;
        try {
            File asrDir = ModelPathResolver.activeDir(asrSpec.targetDir);
            if (asrDir == null) {
                throw new IllegalStateException("Sherpa ASR 模型 active 版本不存在");
            }
            SherpaModelFiles asrFiles = new SherpaModelFiles(
                    new File(asrDir, asrSpec.encoderName()),
                    new File(asrDir, asrSpec.decoderName()),
                    new File(asrDir, asrSpec.joinerName()),
                    new File(asrDir, asrSpec.tokensName()));

            // 可选 Silero 门控：模型未装则 null（无门控全量喂识别器）
            if (downloader.isDownloaded(vadSpec)) {
                File vadDir = ModelPathResolver.activeDir(vadSpec.targetDir);
                vad = new SherpaSileroVad(
                        new File(vadDir, vadSpec.requiredFiles[0]).getAbsolutePath(),
                        VAD_THRESHOLD, VAD_MIN_SILENCE_SECONDS, VAD_MIN_SPEECH_SECONDS,
                        VAD_MAX_SPEECH_SECONDS, null);
            } else {
                Log.w(TAG, "[SherpaAssembly] Silero VAD 未安装，降级为无门控喂识别器");
            }

            try {
                engine = new SherpaAsrEngine(asrFiles, vad, ASR_NUM_THREADS);
            } catch (UnsatisfiedLinkError | ExceptionInInitializerError nativeMissing) {
                // AAR 缺失/ABI 不符时 Kotlin 类初始化即失败 → 装配 fail-closed
                Log.e(TAG, "[SherpaAssembly] native 库不可用（libsherpa-onnx-jni.so）",
                        nativeMissing);
                throw new IllegalStateException(
                        "Sherpa native 库不可用，无法创建 ASR 引擎", nativeMissing);
            }

            SherpaAsrAdapter asr = new SherpaAsrAdapter(engine);
            SherpaEndpointAdapter endpoint = new SherpaEndpointAdapter(engine);
            File kwsDir = ModelPathResolver.activeDir(kwsSpec.targetDir);
            wake = new SherpaWakeAdapter(kwsDir, kwsSpec);
            tts = ttsFactory.create(context.agentExecutor());
            AndroidAudioFocusAdapter focusPort = new AndroidAudioFocusAdapter(app);
            VoiceSessionController controller = new VoiceSessionController(
                    wake, asr, tts, endpoint, focusPort, context.runner(),
                    new ResponsePresenter(), VoicePolicyConfig.defaults(),
                    context.stateExecutor(), context.agentExecutor(),
                    context.timeoutScheduler(), NoopVoiceMetrics.INSTANCE);
            VoiceCaptureController capture = new VoiceCaptureController(controller, wake, asr,
                    VoicePolicyConfig.defaults(), context.appContext(), captureThreadFactory);
            Log.i(TAG, "[SherpaAssembly] create complete vadGate=" + (vad != null));
            return new SherpaAssembly(controller, capture, engine, wake, tts, vad);
        } catch (Exception e) {
            Log.e(TAG, "[SherpaAssembly] create failed type=" + e.getClass().getSimpleName(), e);
            if (wake != null) try { wake.close(); } catch (Exception ignored) { }
            if (engine != null) try { engine.close(); } catch (Exception ignored) { }
            if (vad != null) try { vad.close(); } catch (Exception ignored) { }
            if (tts != null) try { tts.shutdown(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** 一次装配产物；close 按依赖逆序释放（Controller 收敛 → tts → KWS → 引擎 → VAD）。 */
    private static final class SherpaAssembly implements VoiceAssembly {
        private final VoiceSessionController controller;
        private final com.matrix.agent.voice.port.VoiceCapturePort capture;
        private final SherpaAsrEngine engine;
        private final SherpaWakeAdapter wake;
        private final ManagedTtsPort tts;
        private final SherpaSileroVad vad;

        SherpaAssembly(VoiceSessionController controller,
                com.matrix.agent.voice.port.VoiceCapturePort capture,
                SherpaAsrEngine engine, SherpaWakeAdapter wake, ManagedTtsPort tts,
                SherpaSileroVad vad) {
            this.controller = controller;
            this.capture = capture;
            this.engine = engine;
            this.wake = wake;
            this.tts = tts;
            this.vad = vad;
        }

        @Override
        public VoiceSessionController controller() { return controller; }

        @Override
        public com.matrix.agent.voice.port.VoiceCapturePort capture() { return capture; }

        @Override
        public void close() {
            try {
                controller.shutdown(() -> {
                    tts.shutdown();
                    try { wake.close(); } catch (Exception e) {
                        Log.w(TAG, "[SherpaAssembly] close wake: " + e.getMessage());
                    }
                    try { engine.close(); } catch (Exception e) {
                        Log.w(TAG, "[SherpaAssembly] close engine: " + e.getMessage());
                    }
                    if (vad != null) try { vad.close(); } catch (Exception ignored) { }
                });
            } catch (Exception e) {
                Log.w(TAG, "[SherpaAssembly] close: " + e.getMessage());
                // shutdown 提交失败（状态线程已死）：直接兜底回收 native
                try { wake.close(); } catch (Exception ignored) { }
                try { engine.close(); } catch (Exception ignored) { }
                if (vad != null) try { vad.close(); } catch (Exception ignored) { }
                try { tts.shutdown(); } catch (Exception ignored) { }
            }
        }
    }
}
