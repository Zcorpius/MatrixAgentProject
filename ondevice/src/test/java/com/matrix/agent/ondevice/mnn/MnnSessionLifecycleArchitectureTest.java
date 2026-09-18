package com.matrix.agent.ondevice.mnn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Native session memory has an explicit owner; GC and hidden workers are not lifecycle APIs. */
public final class MnnSessionLifecycleArchitectureTest {
    @Test
    public void sessionUsesExplicitCloseAndNeverSpawnsItsOwnReleaseThread() throws IOException {
        String source = new String(Files.readAllBytes(sessionSource()), StandardCharsets.UTF_8);
        assertTrue("native session must expose deterministic ownership",
                source.contains("implements AutoCloseable"));
        assertTrue("close must delegate to the idempotent release operation",
                source.contains("@Override public void close() {\n        release();"));
        assertFalse("finalization is not a native-resource lifecycle", source.contains("finalize("));
        assertFalse("deferred native release must use an injected owner scheduler",
                source.contains("new Thread("));
        assertTrue("production retries must use the injected scheduler",
                source.contains("deferredReleaseScheduler.schedule"));
    }

    private static Path sessionSource() {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path direct = workdir.resolve("src/main/java/com/matrix/agent/ondevice/mnn/MNNLlmSession.java");
        if (Files.isRegularFile(direct)) return direct;
        Path fromRoot = workdir.resolve("ondevice/src/main/java/com/matrix/agent/ondevice/mnn/MNNLlmSession.java");
        assertTrue("MNNLlmSession source must exist: " + fromRoot, Files.isRegularFile(fromRoot));
        return fromRoot;
    }
}
