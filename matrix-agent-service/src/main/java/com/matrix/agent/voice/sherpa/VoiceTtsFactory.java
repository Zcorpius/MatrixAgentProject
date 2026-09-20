package com.matrix.agent.voice.sherpa;

import android.app.Application;
import android.util.Log;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.voice.ModelPathResolver;
import com.matrix.agent.voice.platform.AndroidTtsAdapter;
import com.matrix.agent.voice.port.ManagedTtsPort;
import com.matrix.agent.voice.tencent.FallbackTtsAdapter;
import com.matrix.agent.voice.tencent.SecureTencentTtsConfigStore;
import com.matrix.agent.voice.tencent.TencentCloudTtsAdapter;
import com.matrix.agent.api.voice.TencentTtsConfig;

import java.io.File;
import java.util.concurrent.Executor;

import okhttp3.OkHttpClient;

/**
 * Resolves the voice-output engine independently of ASR selection.
 *
 * <p>Installed Piper is authoritative. Android {@code TextToSpeech} remains a zero-download
 * compatibility fallback for full Android builds, but never masks an installed local model.
 * Both Vosk and Sherpa ASR factories use this one resolver, so switching ASR cannot silently
 * reintroduce the ROM-without-TTS-service failure.</p>
 */
public final class VoiceTtsFactory {
    private static final String TAG = "MatrixAgent";

    private final Application app;
    private final SherpaModelDownloader downloader;
    private final SherpaModelSpec piperSpec;
    private final SecureTencentTtsConfigStore tencentConfig;
    private final OkHttpClient cloudHttpClient;

    public VoiceTtsFactory(Application app, ModelDownloadDao dao, OkHttpClient httpClient) {
        this(app, dao, httpClient, httpClient);
    }

    /** Download and cloud clients are injected separately so their timeout policies stay explicit. */
    public VoiceTtsFactory(Application app, ModelDownloadDao dao, OkHttpClient downloadClient,
            OkHttpClient cloudHttpClient) {
        this.app = app;
        File root = new File(app.getFilesDir(), "sherpa-model");
        downloader = new SherpaModelDownloader(app, dao, downloadClient);
        piperSpec = SherpaModelSpec.piperZhCn(root);
        tencentConfig = new SecureTencentTtsConfigStore(app);
        this.cloudHttpClient = cloudHttpClient;
    }

    public ManagedTtsPort create(Executor executionLane) {
        ManagedTtsPort local = createLocalFallback(executionLane);
        TencentTtsConfig cloud = tencentConfig.projection();
        if (cloud.configured) {
            try {
                // Validate/decrypt before selecting cloud. A key invalidated by factory reset or
                // Keystore rotation cannot masquerade as a configured primary route.
                if (tencentConfig.loadCredentials() != null) {
                    Log.i(TAG, "[Voice] TTS 路由=tencent_cloud，失败自动回退本地引擎");
                    return new FallbackTtsAdapter(new TencentCloudTtsAdapter(tencentConfig, cloud,
                            cloudHttpClient, executionLane), local);
                }
            } catch (Exception unavailable) {
                Log.w(TAG, "[Voice] 腾讯云凭证不可读，使用本地播报 type="
                        + unavailable.getClass().getSimpleName());
            }
        }
        return local;
    }

    private ManagedTtsPort createLocalFallback(Executor executionLane) {
        if (downloader.isDownloaded(piperSpec)) {
            File active = ModelPathResolver.activeDir(piperSpec.targetDir);
            if (active != null) {
                Log.i(TAG, "[Voice] TTS 路由=local_piper");
                return new SherpaPiperTtsAdapter(active, piperSpec, executionLane);
            }
        }
        Log.i(TAG, "[Voice] TTS 路由=android_system（本地 Piper 未安装）");
        return new AndroidTtsAdapter(app);
    }
}
