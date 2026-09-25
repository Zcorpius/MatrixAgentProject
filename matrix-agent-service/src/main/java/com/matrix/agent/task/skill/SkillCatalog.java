package com.matrix.agent.task.skill;

import android.content.Context;
import android.content.res.AssetManager;

import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.task.capability.CapabilityRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads bounded, APK-signed instructions and checks every declared tool dependency. */
public final class SkillCatalog {
    private static final String ROOT = "skills/";
    private static final int MAX_INSTRUCTIONS_BYTES = 4_096;
    private final Map<String, Skill> skills;

    interface Source {
        String[] list(String path) throws IOException;
        InputStream open(String path) throws IOException;
    }

    public SkillCatalog(Context context, CapabilityRegistry registry) {
        this(androidSource(context.getAssets()), registry);
    }

    private static Source androidSource(AssetManager assets) {
        return new Source() {
            @Override public String[] list(String path) throws IOException {
                return assets.list(path);
            }
            @Override public InputStream open(String path) throws IOException {
                return assets.open(path);
            }
        };
    }

    SkillCatalog(Source assets, CapabilityRegistry registry) {
        Map<String, Skill> loaded = new LinkedHashMap<>();
        try {
            String[] directories = assets.list("skills");
            if (directories == null) throw new IOException("skills asset directory missing");
            for (String directory : directories) {
                if (!directory.matches("[a-z][a-z0-9-]{1,63}")) {
                    throw new IllegalStateException("invalid skill directory");
                }
                JSONObject manifest = new JSONObject(read(assets, ROOT + directory
                        + "/manifest.json", 2_048));
                String id = manifest.getString("id");
                if (!directory.equals(id) || manifest.getInt("version") != 1
                        || loaded.containsKey(id)) {
                    throw new IllegalStateException("invalid or duplicate skill: " + directory);
                }
                String instructionFile = manifest.getString("instructions");
                if (!"SKILL.md".equals(instructionFile)) {
                    throw new IllegalStateException("unexpected skill instruction path");
                }
                JSONArray capabilities = manifest.getJSONArray("required_capabilities");
                for (int index = 0; index < capabilities.length(); index++) {
                    if (registry.find(capabilities.getString(index)) == null) {
                        throw new IllegalStateException("unregistered skill dependency: " + id);
                    }
                }
                JSONArray profiles = manifest.getJSONArray("profiles");
                java.util.EnumSet<RuntimeProfile> allowed =
                        java.util.EnumSet.noneOf(RuntimeProfile.class);
                for (int index = 0; index < profiles.length(); index++) {
                    allowed.add(RuntimeProfile.valueOf(profiles.getString(index)));
                }
                loaded.put(id, new Skill(id, allowed, read(assets,
                        ROOT + directory + "/" + instructionFile, MAX_INSTRUCTIONS_BYTES)));
            }
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("failed to load signed media skills", error);
        }
        skills = Collections.unmodifiableMap(loaded);
    }

    public Skill get(String id) { return skills.get(id); }

    private static String read(Source assets, String path, int limit) throws IOException {
        try (InputStream input = assets.open(path);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > limit) throw new IOException("skill asset too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static final class Skill {
        public final String id;
        public final java.util.Set<RuntimeProfile> profiles;
        public final String instructions;

        private Skill(String id, java.util.Set<RuntimeProfile> profiles, String instructions) {
            this.id = id;
            this.profiles = Collections.unmodifiableSet(java.util.EnumSet.copyOf(profiles));
            this.instructions = instructions;
        }
    }
}
