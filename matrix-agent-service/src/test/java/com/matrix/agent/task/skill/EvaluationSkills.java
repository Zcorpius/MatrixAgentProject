package com.matrix.agent.task.skill;

import com.matrix.agent.task.capability.CapabilityRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only adapter: load exactly the production APK assets without widening the signed-source API. */
public final class EvaluationSkills {
    private EvaluationSkills() {}

    public static SkillCatalog load(Path assets, CapabilityRegistry registry) {
        return new SkillCatalog(new SkillCatalog.Source() {
            @Override public String[] list(String path) throws IOException {
                try (var files = Files.list(assets.resolve(path))) {
                    return files.map(file -> file.getFileName().toString()).sorted().toArray(String[]::new);
                }
            }
            @Override public InputStream open(String path) throws IOException {
                return Files.newInputStream(assets.resolve(path));
            }
        }, registry);
    }
}
