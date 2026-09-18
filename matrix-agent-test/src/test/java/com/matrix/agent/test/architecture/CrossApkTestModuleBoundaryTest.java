package com.matrix.agent.test.architecture;

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
 * Keeps the cross-APK verifier honest: it is a client of the published SDK, never a second
 * in-process Host test suite.
 *
 * <p>The trusted/untrusted flavors deliberately differ only in signing and permission. If this
 * module acquires a Host implementation dependency, both acceptance tests can appear green while
 * bypassing the Binder contract that a separately installed product actually uses.</p>
 */
public final class CrossApkTestModuleBoundaryTest {
    @Test
    public void testApkDependsOnlyOnThePublishedSdk() throws IOException {
        String gradle = read(moduleRoot().resolve("build.gradle.kts"));
        assertTrue("cross-APK verifier must compile against service-lib",
                gradle.contains("implementation(project(\":matrix-agent-service-lib\"))"));
        assertFalse("cross-APK verifier must not link the Host implementation",
                gradle.contains("project(\":matrix-agent-service\")"));
        assertFalse("cross-APK verifier must not link the on-device implementation",
                gradle.contains("project(\":ondevice\")"));
        assertFalse("cross-APK verifier must not link Launcher implementation",
                gradle.contains("project(\":matrix-agent-launcher\")"));
    }

    @Test
    public void instrumentationSourcesUseOnlyPublicSdkNamespaces() throws IOException {
        try (Stream<Path> sources = Files.walk(moduleRoot().resolve("src"))) {
            for (Path source : (Iterable<Path>) sources
                    .filter(path -> path.toString().endsWith(".java"))::iterator) {
                String text = read(source);
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

    private static void assertNoImport(String text, Path source, String implementationPackage) {
        assertFalse("cross-APK verifier must not import implementation package "
                        + implementationPackage + ": " + source,
                text.contains("import " + implementationPackage));
    }

    private static String read(Path source) throws IOException {
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static Path moduleRoot() {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path direct = workdir;
        if (Files.isRegularFile(direct.resolve("build.gradle.kts"))
                && Files.isDirectory(direct.resolve("src"))) return direct;
        Path fromRoot = workdir.resolve("matrix-agent-test");
        assertTrue("matrix-agent-test module must exist: " + fromRoot, Files.isDirectory(fromRoot));
        return fromRoot;
    }
}
