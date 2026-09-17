package com.matrix.agent.voice.vosk;

import android.app.Application;
import android.util.Log;

import com.matrix.agent.voice.platform.AndroidAudioFocusAdapter;
import com.matrix.agent.voice.platform.AndroidTtsAdapter;
import com.matrix.agent.voice.platform.VoiceCaptureController;
import com.matrix.agent.voice.platform.VoskResultParser;
import com.matrix.agent.voice.AgentRunner;
import com.matrix.agent.voice.ModelInstallLock;
import com.matrix.agent.voice.NoopVoiceMetrics;
import com.matrix.agent.voice.ResponsePresenter;
import com.matrix.agent.voice.RetryPolicy;
import com.matrix.agent.voice.VoiceAssembly;
import com.matrix.agent.voice.VoiceAssemblyFactory;
import com.matrix.agent.voice.port.VoiceCapturePort;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.voice.VoskModelDownloader;
import com.matrix.agent.voice.VoskModelSpec;
import com.matrix.agent.voice.VoiceSessionController;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Vosk 引擎装配工厂(仅 debug 源集;阶段 3 批 A 从 {@code VoiceRuntime.buildAndStart} 收敛而来)。
 *
 * <p>{@link #prepare}:模型就绪(官方 zip 下载/SHA-256 校验/统一模型锁),锁竞争
 * (LockBusy)60s 有界退避自行接管缺失模型——对齐 follow-up1~13 的既有语义。
 * {@link #create}:构造 Vosk 端口(VoskModelHolder/Engine/ASR/Endpoint/Wake)+ AndroidTts/
 * AudioFocus + Controller + 采音。{@code VoskAssembly.close}:Controller shutdown
 * (串行 stop Recognizer)后释放 tts 引擎与 Model(native 生命周期:Recognizer 先于 Model close)。
 */
public final class VoskVoiceAssemblyFactory implements VoiceAssemblyFactory {
    private static final String TAG = "MatrixAgent";

    private final Application app;
    private final VoskModelDownloader downloader;

    public VoskVoiceAssemblyFactory(Application app, ModelDownloadDao dao) {
        if (app == null) throw new IllegalArgumentException("app 不能为空");
        this.app = app;
        this.downloader = new VoskModelDownloader(app, dao); // dao 可空:数据库降级时进度降级
    }

    @Override
    public void prepare(ProgressListener progress, BooleanSupplier cancelled) throws Exception {
        File voskRoot = new File(app.getFilesDir(), "vosk-model");
        final VoskModelSpec en = VoskModelSpec.en(voskRoot);
        final VoskModelSpec cn = VoskModelSpec.cn(voskRoot);
        try {
            ensureModels(en, cn, progress, cancelled);
        } catch (ModelInstallLock.LockBusyException e) {
            // 锁竞争→退避重试 download(自己接管缺失模型,不要求另一实例下完两个)
            Log.i(TAG, "[Voice] 模型正在被另一实例安装,退避重试");
            progress.onProgress("模型正在安装,等待后重试…");
            retryDownloadWithBackoff(en, cn, progress, cancelled);
        } catch (java.io.IOException e) {
            throw e; // 真实下载失败上浮(Runtime 报"启动失败(异常类型)")
        }
    }

    private void ensureModels(VoskModelSpec en, VoskModelSpec cn,
            ProgressListener progress, BooleanSupplier cancelled) throws java.io.IOException {
        if (!downloader.isDownloaded(en)) {
            progress.onProgress("下载英文唤醒模型(~40MB)…");
            downloader.download(en, cancelled);
        }
        if (cancelled.getAsBoolean()) return;
        if (!downloader.isDownloaded(cn)) {
            progress.onProgress("下载中文识别模型(~42MB)…");
            downloader.download(cn, cancelled);
        }
    }

    /** LockBusy 后 60s 有界退避重试 download(接管缺失模型);经 RetryPolicy(可注入 clock/sleeper 测试)。 */
    private void retryDownloadWithBackoff(VoskModelSpec en, VoskModelSpec cn,
            ProgressListener progress, BooleanSupplier cancelled) throws java.io.IOException {
        try {
            boolean ok = RetryPolicy.retry(System::nanoTime, Thread::sleep,
                    System.nanoTime() + 60_000_000_000L, new long[]{500, 1000, 2000, 2000},
                    () -> cancelled.getAsBoolean(),
                    () -> {
                        try {
                            if (!downloader.isDownloaded(en)) downloader.download(en, cancelled);
                            if (cancelled.getAsBoolean()) throw new RetryPolicy.RetryableFailure();
                            if (!downloader.isDownloaded(cn)) downloader.download(cn, cancelled);
                            if (!(downloader.isDownloaded(en) && downloader.isDownloaded(cn))) {
                                throw new RetryPolicy.RetryableFailure(); // 仍未完成,继续退避
                            }
                        } catch (ModelInstallLock.LockBusyException be) {
                            throw new RetryPolicy.RetryableFailure(); // 锁竞争,继续退避
                        }
                    });
            if (ok) return;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        throw new java.io.IOException("模型安装重试超时");
    }

    @Override
    public VoiceAssembly create(AssemblyContext context) throws Exception {
        // P2:中途异常按 recognizer → TTS → Model 顺序释放已建资源(恢复旧 cleanupLocal 语义,防 native 泄漏)
        VoskModelHolder holder = null;
        VoskAsrAdapter asr = null;
        VoskEndpointAdapter endpoint = null;
        VoskWakeAdapter wake = null;
        AndroidTtsAdapter tts = null;
        try {
            holder = new VoskModelHolder(new File(app.getFilesDir(), "vosk-model"));
            VoskAsrEngine engine = new VoskAsrEngine(holder.cnModel());
            asr = new VoskAsrAdapter(engine);
            endpoint = new VoskEndpointAdapter(engine);
            wake = new VoskWakeAdapter(holder.enModel());
            tts = new AndroidTtsAdapter(app);
            AndroidAudioFocusAdapter focusPort = new AndroidAudioFocusAdapter(app);
            VoiceSessionController controller = new VoiceSessionController(
                    wake, asr, tts, endpoint, focusPort, context.runner(), new ResponsePresenter(),
                    VoicePolicyConfig.defaults(), context.stateExecutor(), context.agentExecutor(),
                    context.timeoutScheduler(), NoopVoiceMetrics.INSTANCE);
            VoiceCaptureController capture = new VoiceCaptureController(controller, wake, asr,
                    VoicePolicyConfig.defaults(), context.appContext());
            return new VoskAssembly(controller, capture, wake, asr, tts, holder);
        } catch (Exception e) {
            if (wake != null) try { wake.stop(); } catch (Exception ignored) { } // recognizer 先
            if (asr != null) try { asr.stop(); } catch (Exception ignored) { }
            if (tts != null) try { tts.shutdown(); } catch (Exception ignored) { }
            if (holder != null) try { holder.close(); } catch (Exception ignored) { } // Model 最后
            throw e;
        }
    }

    /** 一次装配产物;close 完成 native 生命周期回收(Recognizer 先于 Model close)。 */
    private static final class VoskAssembly implements VoiceAssembly {
        private final VoiceSessionController controller;
        private final VoiceCapturePort capture;
        private final VoskWakeAdapter wake;
        private final VoskAsrAdapter asr;
        private final AndroidTtsAdapter tts;
        private final VoskModelHolder holder;

        VoskAssembly(VoiceSessionController controller, VoiceCapturePort capture,
                VoskWakeAdapter wake, VoskAsrAdapter asr, AndroidTtsAdapter tts, VoskModelHolder holder) {
            this.controller = controller;
            this.capture = capture;
            this.wake = wake;
            this.asr = asr;
            this.tts = tts;
            this.holder = holder;
        }

        @Override public VoiceSessionController controller() { return controller; }
        @Override public VoiceCapturePort capture() { return capture; }

        @Override public void close() {
            // Controller.shutdown 内串行 stop asr/wake(close Recognizer);afterStopped 在
            // Recognizer 关闭后关 tts 引擎 + Model(顺序保证)。未 start 的部分(failed create)兜底停。
            try {
                controller.shutdown(() -> {
                    tts.shutdown();
                    try { holder.close(); } catch (Exception e) { Log.w(TAG, "[Voice] close holder: " + e.getMessage()); }
                });
            } catch (Exception e) {
                Log.w(TAG, "[Voice] assembly close: " + e.getMessage());
                // shutdown 提交失败(状态线程已死):直接兜底回收 native
                try { wake.stop(); } catch (Exception ignored) { }
                try { asr.stop(); } catch (Exception ignored) { }
                try { tts.shutdown(); } catch (Exception ignored) { }
                try { holder.close(); } catch (Exception ignored) { }
            }
        }
    }
}
