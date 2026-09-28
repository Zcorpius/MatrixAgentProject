package com.matrix.agent.embedding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class EmbeddingPreparationTest {
    @Test public void requestTimeoutDoesNotRestartAnInstallingArtifact() throws Exception {
        File root = Files.createTempDirectory("embedding-preparation").toFile();
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var opens = new AtomicInteger();
        try (var preparation = new EmbeddingPreparation(
                new EmbeddingArtifact(root, EmbeddingArtifactTest.manifest()), name -> {
                    if (opens.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("test install timeout");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    return new ByteArrayInputStream(name.getBytes());
                }, executor)) {
            assertThrows(TimeoutException.class,
                    () -> preparation.await(30, TimeUnit.MILLISECONDS, () -> false));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            release.countDown();
            File config = preparation.await(2, TimeUnit.SECONDS, () -> false);
            assertEquals("config.json", config.getName());
            assertEquals(6, opens.get());
            assertEquals(config, preparation.await(1, TimeUnit.SECONDS, () -> false));
            assertEquals(6, opens.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            try (var paths = Files.walk(root.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
