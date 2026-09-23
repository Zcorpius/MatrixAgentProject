package com.matrix.agent.voice.sherpa;

import android.app.Application;
import android.util.Log;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.voice.ModelPathResolver;
import com.matrix.agent.voice.platform.AndroidTtsAdapter;
import com.matrix.agent.voice.port.ManagedTtsPort;
import com.matrix.agent.voice.tencent.TencentCloudTtsRouteFactory;

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
    private final TencentCloudTtsRouteFactory cloudRoute;

    public VoiceTtsFactory(Application app, ModelDownloadDao dao, OkHttpClient httpClient) {
        this(app, dao, httpClient, httpClient);
    }

    /** Download and cloud clients are injected separately so their timeout policies stay explicit. */
    public VoiceTtsFactory(Application app, ModelDownloadDao dao, OkHttpClient downloadClient,
            OkHttpClient cloudHttpClient) {
        this.app = app;
        File root = new File(app.getFilesDir(), "sherpa-model");
        downloader = new SherpaModelDownloader(app, dao, downloadClient,
                SherpaModelDownloader.systemPresetRoot());
        piperSpec = SherpaModelSpec.piperZhCn(root);
        cloudRoute = new TencentCloudTtsRouteFactory(app, cloudHttpClient);
    }

    public ManagedTtsPort create(Executor executionLane) {
        ManagedTtsPort local = createLocalFallback(executionLane);
        return cloudRoute.create(() -> local, executionLane);
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
