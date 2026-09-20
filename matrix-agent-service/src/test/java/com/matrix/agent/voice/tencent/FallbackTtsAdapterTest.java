package com.matrix.agent.voice.tencent;

import static org.junit.Assert.assertEquals;

import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.ManagedTtsPort;
import com.matrix.agent.task.TaskState;

import org.junit.Test;

/** A primary cloud error must be invisible to the controller when local playback succeeds. */
public final class FallbackTtsAdapterTest {
    @Test public void cloudError_retriesSameUtteranceOnLocalPort() {
        FakePort cloud = new FakePort();
        FakePort local = new FakePort();
        FallbackTtsAdapter adapter = new FallbackTtsAdapter(cloud, local);
        Recording listener = new Recording();
        adapter.setListener(listener);
        adapter.speak(new SpeakableResponse("你好", "zh-CN", TaskState.SUCCEEDED), "u-1");
        cloud.listener.onError("u-1", "TTS_CLOUD_REQUEST_FAILED");
        assertEquals("u-1", local.lastId);
        local.listener.onDone("u-1");
        assertEquals(1, listener.done);
        assertEquals(0, listener.errors);
    }

    private static final class FakePort implements ManagedTtsPort {
        Listener listener; String lastId;
        @Override public void setListener(Listener listener) { this.listener = listener; }
        @Override public void speak(SpeakableResponse response, String id) { lastId = id; }
        @Override public void stop() { }
        @Override public void shutdown() { }
    }
    private static final class Recording implements ManagedTtsPort.Listener {
        int done; int errors;
        @Override public void onDone(String id) { done++; }
        @Override public void onError(String id, String code) { errors++; }
    }
}
