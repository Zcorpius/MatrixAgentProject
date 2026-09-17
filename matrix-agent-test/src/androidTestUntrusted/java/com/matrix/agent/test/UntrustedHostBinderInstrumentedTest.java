package com.matrix.agent.test;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.client.MatrixAgent;

import org.junit.Test;
import org.junit.runner.RunWith;

/** A separately signed/permissionless flavor must never negotiate the protected Host binder. */
@RunWith(AndroidJUnit4.class)
public final class UntrustedHostBinderInstrumentedTest {
    @Test public void untrustedClientCannotConnectToHost() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MatrixAgent agent = MatrixAgent.create(context, null, 2_000L, null);
        try {
            assertTrue(agent.getState() == ConnectionState.SERVICE_NOT_READY
                    || agent.getState() == ConnectionState.DISCONNECTED
                    || agent.getState() == ConnectionState.CONNECTING);
            assertTrue(agent.getAgentManager() == null);
        } finally {
            agent.release();
        }
    }
}
