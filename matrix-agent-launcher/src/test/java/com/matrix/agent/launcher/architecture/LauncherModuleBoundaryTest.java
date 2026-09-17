package com.matrix.agent.launcher.architecture;

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
 * Keeps Launcher independently deployable from the system Host implementation.
 *
 * <p>API DTOs and {@code service-lib} client managers are the only legal cross-APK contract.
 * A tempting direct import from the service module would compile locally but makes the Launcher
 * impossible to ship, test, or evolve independently.</p>
 */
public final class LauncherModuleBoundaryTest {
    @Test
    public void launcherNeverImportsHostImplementationPackages() throws IOException {
        try (Stream<Path> sources = Files.walk(mainJava())) {
            for (Path source : (Iterable<Path>) sources.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                assertFalse("Launcher must not import Host implementation: " + source,
                        text.contains("import com.matrix.agent.host."));
                assertFalse("Launcher must not import service task implementation: " + source,
                        text.contains("import com.matrix.agent.task."));
                assertFalse("Launcher must not import service data implementation: " + source,
                        text.contains("import com.matrix.agent.data."));
                assertFalse("Launcher must not import service model implementation: " + source,
                        text.contains("import com.matrix.agent.model."));
            }
        }
    }

    @Test
    public void presentationNeverTalksToSdkManagersDirectly() throws IOException {
        Path presentation = mainJava().resolve("com/matrix/agent/launcher/presentation");
        try (Stream<Path> sources = Files.walk(presentation)) {
            for (Path source : (Iterable<Path>) sources.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                assertFalse("Presentation must use a repository, not a SDK manager: " + source,
                        text.contains("import com.matrix.agent.client."));
            }
        }
    }

    private static Path mainJava() {
        Path workdir = Paths.get(System.getProperty("user.dir"));
        Path module = workdir.resolve("src/main/java");
        if (Files.isDirectory(module)) return module;
        Path fromRoot = workdir.resolve("matrix-agent-launcher/src/main/java");
        assertTrue("Launcher source root must exist: " + fromRoot, Files.isDirectory(fromRoot));
        return fromRoot;
    }
}
