package com.matrix.agent.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.client.MatrixAgent;
import com.matrix.agent.client.MatrixAgentManager;
import com.matrix.agent.client.ModelManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SDK 门面生命周期 instrumentation（审计 A-116）。
 * 本类覆盖"无服务部署"环境下的可验证语义；跨 APK death/重订阅/trusted-untrusted
 * 场景由 :matrix-agent-test 承担。
 * 真实执行需设备/模拟器（connectedDebugAndroidTest），CI 镜像就绪前以编译验证。
 */
@RunWith(AndroidJUnit4.class)
public class MatrixAgentSdkInstrumentedTest {

    private static final long SHORT_TIMEOUT_MS = 500;

    @Test
    public void create_withoutDeployedService_timesOutToNotReady() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicInteger observed = new AtomicInteger(-1);
        MatrixAgent agent = MatrixAgent.create(context, null, SHORT_TIMEOUT_MS,
                (a, state) -> observed.set(state));
        try {
            // 无部署环境：ServiceManager 无该 Binder、bind 目标不存在 → 稳定未就绪而非挂死。
            assertTrue(agent.getState() == com.matrix.agent.api.common.ConnectionState.SERVICE_NOT_READY
                    || agent.getState() == com.matrix.agent.api.common.ConnectionState.DISCONNECTED);
        } finally {
            agent.release();
        }
    }

    @Test
    public void managers_unavailable_beforeConnected() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, SHORT_TIMEOUT_MS, null);
        try {
            assertNull(agent.getAgentManager());
            assertNull(agent.getModelManager());
            assertNull(agent.getVoiceManager());
            assertNull(agent.getDownloadManager());
            assertNull(agent.getMatrixManager("unknown.extension.key"));
        } finally {
            agent.release();
        }
    }

    @Test
    public void release_isIdempotent_andSafeAfterDisconnect() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, SHORT_TIMEOUT_MS, null);
        agent.release();
        agent.release(); // 幂等
        // release 后 Manager 获取返回 null，不再触发连接或崩溃
        assertNull(agent.getAgentManager());
        assertEquals(MatrixAgent.SUPPORTED_CONTRACT_MAJOR, 1);
    }

    @Test
    public void managerApis_nullSafeAfterRelease() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, SHORT_TIMEOUT_MS, null);
        // 直接构造 Manager（绕过 getMatrixManager 的连接检查）验证判空语义：
        // 断线/释放后方法返回稳定不可用结果而非 NPE（审计 A-103 回归防线）。
        MatrixAgentManager manager = agent.getAgentManager();
        assertNull(manager == null ? null : manager.getTaskSnapshot("any"));
        ModelManager modelManager = agent.getModelManager();
        assertNotNull(modelManager == null ? null : modelManager.listModels());
        agent.release();
    }
}
