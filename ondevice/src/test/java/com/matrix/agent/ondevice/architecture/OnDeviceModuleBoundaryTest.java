package com.matrix.agent.ondevice.architecture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Keeps the MNN adapter reusable and free of Host/SDK/UI policy dependencies. */
public final class OnDeviceModuleBoundaryTest {
    @Test
    public void onDeviceAdapterNeverImportsOtherMatrixModules() throws IOException {
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
                assertNoImport(text, source, "com.matrix.agent.launcher.");
                assertNoImport(text, source, "com.matrix.agent.client.");
                assertNoImport(text, source, "com.matrix.agent.api.");
            }
        }
    }

    private boolean isJava(Path path) {
        return path.toString().endsWith(".java");
    }

    private static void assertNoImport(String text, Path source, String implementationPackage) {
        assertFalse("On-device adapter must not import " + implementationPackage + ": " + source,
                text.contains("import " + implementationPackage));
    }

    private static Path mainJava() {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path module = workdir.resolve("src/main/java");
        if (Files.isDirectory(module)) return module;
        Path fromRoot = workdir.resolve("ondevice/src/main/java");
        assertTrue("On-device source root must exist: " + fromRoot, Files.isDirectory(fromRoot));
        return fromRoot;
    }
}
