package com.matrix.agent.voice.tencent;

import android.util.Log;

import com.matrix.agent.api.voice.TencentTtsConfig;
import com.matrix.agent.voice.port.ManagedTtsPort;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;

/**
 * Resolves the optional Tencent route at one process boundary.
 *
 * <p>The caller owns the fallback factory: a voice session normally supplies Piper first,
 * whereas on-demand conversation readback deliberately supplies the Android platform engine.
 * This keeps a readback request from loading a second native Piper model merely to speak one
 * saved message, while credential validation and cloud-fallback semantics remain identical for
 * both product surfaces.</p>
 */
public final class TencentCloudTtsRouteFactory {
    private static final String TAG = "MatrixAgent";

    private final SecureTencentTtsConfigStore configStore;
    private final OkHttpClient cloudHttpClient;

    public TencentCloudTtsRouteFactory(android.app.Application application,
            OkHttpClient cloudHttpClient) {
        if (application == null) throw new IllegalArgumentException("application 不能为空");
        this.configStore = new SecureTencentTtsConfigStore(application);
        this.cloudHttpClient = Objects.requireNonNull(cloudHttpClient, "cloudHttpClient");
    }

    /**
     * Returns Tencent-cloud primary plus the supplied local fallback when credentials are
     * readable; otherwise returns only that fallback.  Credentials never leave this method
     * except transiently inside {@link TencentCloudTtsAdapter} while it signs one request.
     */
    public ManagedTtsPort create(Supplier<ManagedTtsPort> fallbackFactory,
            Executor executionLane) {
        if (fallbackFactory == null) throw new IllegalArgumentException("fallbackFactory 不能为空");
        if (executionLane == null) throw new IllegalArgumentException("executionLane 不能为空");
        ManagedTtsPort fallback = Objects.requireNonNull(fallbackFactory.get(), "fallback");
        TencentTtsConfig config = configStore.projection();
        if (!config.configured) return fallback;
        try {
            // A stale SharedPreferences marker after a Keystore reset is not a usable route.
            if (configStore.loadCredentials() == null) return fallback;
            Log.i(TAG, "[Voice] TTS 路由=tencent_cloud，失败自动回退本地引擎");
            return new FallbackTtsAdapter(new TencentCloudTtsAdapter(configStore, config,
                    cloudHttpClient, executionLane), fallback);
        } catch (Exception unavailable) {
            Log.w(TAG, "[Voice] 腾讯云凭证不可读，使用本地播报 type="
                    + unavailable.getClass().getSimpleName());
            return fallback;
        }
    }
}
