package com.matrix.agent.task.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.task.capability.CapabilityRegistry;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class SkillCatalogTest {
    private static final String MANIFEST = """
            {"id":"bilibili-open-video","version":1,
             "required_capabilities":["media.bilibili.search_videos"],
             "profiles":["PHONE","AUTOMOTIVE"],"instructions":"SKILL.md"}
            """;

    @Test public void loadsSignedInstructionsAndChecksCapabilities() {
        SkillCatalog catalog = catalog(MANIFEST, "搜索后等待用户选择");
        SkillCatalog.Skill skill = catalog.get("bilibili-open-video");
        assertEquals("搜索后等待用户选择", skill.instructions);
        assertTrue(skill.profiles.contains(RuntimeProfile.PHONE));
        assertTrue(skill.profiles.contains(RuntimeProfile.AUTOMOTIVE));
    }

    @Test public void rejectsMissingCapabilityAndOversizedInstructions() {
        assertThrows(IllegalStateException.class, () -> catalog(
                MANIFEST.replace("media.bilibili.search_videos", "media.bilibili.unregistered"),
                "valid"));
        assertThrows(IllegalStateException.class, () -> catalog(MANIFEST, "x".repeat(4_097)));
    }

    private static SkillCatalog catalog(String manifest, String instructions) {
        Map<String, String> files = Map.of(
                "skills/bilibili-open-video/manifest.json", manifest,
                "skills/bilibili-open-video/SKILL.md", instructions);
        SkillCatalog.Source source = new SkillCatalog.Source() {
            @Override public String[] list(String path) {
                return new String[] {"bilibili-open-video"};
            }
            @Override public InputStream open(String path) throws IOException {
                String content = files.get(path);
                if (content == null) throw new IOException("missing asset");
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
        return new SkillCatalog(source, CapabilityRegistry.createRuntimeRegistry());
    }
}
