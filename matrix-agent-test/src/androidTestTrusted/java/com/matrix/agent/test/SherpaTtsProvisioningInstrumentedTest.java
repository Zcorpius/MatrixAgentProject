package com.matrix.agent.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.client.MatrixAgent;
import com.matrix.agent.client.VoiceManager;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

/**
 * Explicit true-device provisioning gate for the app-owned Piper voice. It is opt-in because it
 * transfers a verified 58 MB model; normal instrumentation never silently consumes that data.
 */
@RunWith(AndroidJUnit4.class)
public final class SherpaTtsProvisioningInstrumentedTest {
    private static final String PIPER_ID = "sherpa-tts-zh-xiaoya";
    private static final long INSTALL_BUDGET_MS = 180_000L;

    @Test public void downloadsAndVerifiesLocalPiperWhenExplicitlyEnabled() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("需要 -e provisionSherpaTts true",
                "true".equals(arguments.getString("provisionSherpaTts")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 8_000L, null);
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState());
            VoiceManager voice = agent.getVoiceManager();
            assertNotNull(voice);
            ModelDownloadInfo before = piper(voice.listOfflineModels());
            if (before.state != ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED) {
                VoiceOperationResult accepted = voice.installOfflineModels(UUID.randomUUID().toString());
                assertEquals(MatrixErrorCode.SUCCESS, accepted.code);
            }
            long deadline = SystemClock.elapsedRealtime() + INSTALL_BUDGET_MS;
            // installOfflineModels is asynchronous. A persisted FAILED state from an earlier
            // attempt remains visible until the Host worker has claimed and reset it, so do not
            // mistake that short hand-off interval for a failure of this retry.
            long failureEligibleAt = SystemClock.elapsedRealtime() + 3_000L;
            ModelDownloadInfo current;
            do {
                current = piper(voice.listOfflineModels());
                if (current.state == ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED) break;
                assertTrue("Piper 下载失败，状态=" + current.state + " code=" + current.errorCode,
                        current.state != ModelDownloadInfo.DOWNLOAD_STATE_FAILED
                                || SystemClock.elapsedRealtime() < failureEligibleAt);
                SystemClock.sleep(500L);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertEquals("Piper 未在预算内完成", ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED,
                    current.state);
            assertTrue("Piper 安装体积异常: " + current.bytesDownloaded,
                    current.bytesDownloaded >= 50L * 1024L * 1024L);
        } finally {
            agent.release();
        }
    }

    private static ModelDownloadInfo piper(List<ModelDownloadInfo> models) {
        for (ModelDownloadInfo item : models) {
            if (PIPER_ID.equals(item.modelId)) return item;
        }
        throw new AssertionError("Host 未投影 Piper 模型: " + models.size());
    }
}
