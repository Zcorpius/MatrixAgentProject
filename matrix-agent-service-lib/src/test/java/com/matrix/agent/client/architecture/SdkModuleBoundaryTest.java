package com.matrix.agent.client.architecture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * The published SDK is a contract/client module, never a back door to Host implementation.
 *
 * <p>Gradle already prevents direct project dependencies, but source-level imports can regress
 * when classes are temporarily moved between modules. Keep the public artifact independently
 * publishable by making that rule executable.</p>
 */
public final class SdkModuleBoundaryTest {
    @Test
    public void sdkNeverImportsHostOrFeatureImplementations() throws IOException {
        try (Stream<Path> sources = Files.walk(mainJava())) {
            for (Path source : (Iterable<Path>) sources.filter(this::isJava)::iterator) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                assertNoImport(text, source, "com.matrix.agent.host.");
                assertNoImport(text, source, "com.matrix.agent.task.");
                assertNoImport(text, source, "com.matrix.agent.data.");
                assertNoImport(text, source, "com.matrix.agent.model.");
                assertNoImport(text, source, "com.matrix.agent.download.");
                assertNoImport(text, source, "com.matrix.agent.voice.");
                assertNoImport(text, source, "com.matrix.agent.platform.");
                assertNoImport(text, source, "com.matrix.agent.ondevice.");
                assertNoImport(text, source, "com.matrix.agent.launcher.");
            }
        }
    }

    private boolean isJava(Path path) {
        return path.toString().endsWith(".java");
    }

    private static void assertNoImport(String text, Path source, String implementationPackage) {
        assertFalse("SDK must not import implementation package " + implementationPackage + ": " + source,
                text.contains("import " + implementationPackage));
    }

    private static Path mainJava() {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path module = workdir.resolve("src/main/java");
        if (Files.isDirectory(module)) return module;
        Path fromRoot = workdir.resolve("matrix-agent-service-lib/src/main/java");
        assertTrue("SDK source root must exist: " + fromRoot, Files.isDirectory(fromRoot));
        return fromRoot;
    }
}
