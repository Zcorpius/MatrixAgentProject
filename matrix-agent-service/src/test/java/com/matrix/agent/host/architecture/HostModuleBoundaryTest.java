package com.matrix.agent.host.architecture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** The Host may depend on the public API and on-device adapter, but never on a client/UI module. */
public final class HostModuleBoundaryTest {
    @Test
    public void hostNeverImportsSdkClientOrLauncherPresentation() throws IOException {
        try (Stream<Path> sources = Files.walk(mainJava())) {
            for (Path source : (Iterable<Path>) sources.filter(this::isJava)::iterator) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                assertFalse("Host must not import SDK client implementation: " + source,
                        text.contains("import com.matrix.agent.client."));
                assertFalse("Host must not import Launcher implementation: " + source,
                        text.contains("import com.matrix.agent.launcher."));
            }
        }
    }

    private boolean isJava(Path path) {
        return path.toString().endsWith(".java");
    }

    private static Path mainJava() {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path module = workdir.resolve("src/main/java");
        if (Files.isDirectory(module)) return module;
        Path fromRoot = workdir.resolve("matrix-agent-service/src/main/java");
        assertTrue("Host source root must exist: " + fromRoot, Files.isDirectory(fromRoot));
        return fromRoot;
    }
}
