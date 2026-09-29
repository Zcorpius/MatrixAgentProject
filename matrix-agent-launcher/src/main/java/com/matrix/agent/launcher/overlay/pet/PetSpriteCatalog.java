package com.matrix.agent.launcher.overlay.pet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/** Shared bundled sprite contract. Paths are relative to a character's asset directory. */
record PetSpriteCatalog(int width, int height, Map<String, List<Frame>> animations) {
    record Frame(String file, int durationMs) {}

    PetSpriteCatalog {
        animations = Map.copyOf(animations);
    }

    static PetSpriteCatalog parse(String json) throws JSONException {
        return parse(json, PetCharacter.YUKINO);
    }

    static PetSpriteCatalog parse(String json, PetCharacter character) throws JSONException {
        JSONObject root = new JSONObject(json);
        String expectedSchema = character == PetCharacter.YUKINO
                ? "yukino-android-sprite-assets" : "codex-v2-android-sprite-assets";
        if (!expectedSchema.equals(root.getString("schema")) || root.getInt("schemaVersion") != 1
                || !character.displayName().equals(root.getString("displayName"))
                || (character.petId() != null && !character.petId().equals(root.getString("petId")))) {
            throw new JSONException("Unsupported pet asset schema");
        }
        JSONObject atlas = root.getJSONObject("atlas");
        int width = atlas.getInt("cellWidth"), height = atlas.getInt("cellHeight");
        if (width != 192 || height != 208 || atlas.getInt("columns") != 8 || atlas.getInt("rows") != 11) {
            throw new JSONException("Unexpected pet frame dimensions");
        }
        Map<String, List<Frame>> animations = new LinkedHashMap<>();
        JSONObject definitions = root.getJSONObject("animations");
        var keys = definitions.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject animation = definitions.getJSONObject(key);
            JSONArray frames = animation.getJSONArray("frames");
            if (frames.length() == 0 || frames.length() > 32) throw new JSONException("Invalid frame count: " + key);
            List<Frame> values = new ArrayList<>();
            int total = 0;
            for (int i = 0; i < frames.length(); i++) {
                JSONObject frame = frames.getJSONObject(i);
                int duration = frame.getInt("durationMs");
                if (duration <= 0 || duration > 60_000) throw new JSONException("Invalid frame duration: " + key);
                values.add(new Frame(frameFile(frame), duration));
                total += duration;
            }
            if (animation.getInt("totalDurationMs") != total) throw new JSONException("Duration mismatch: " + key);
            animations.put(key, List.copyOf(values));
        }
        animations.put("neutral", List.of(new Frame(frameFile(root.getJSONObject("neutral")), 1)));
        JSONArray directions = root.getJSONObject("look").getJSONArray("frames");
        if (directions.length() != 16) throw new JSONException("Expected 16 look directions");
        List<Frame> look = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            JSONObject direction = directions.getJSONObject(i);
            if (direction.getDouble("angleDegreesClockwiseFromUp") != i * 22.5) {
                throw new JSONException("Unexpected look direction order");
            }
            look.add(new Frame(frameFile(direction), 1));
        }
        animations.put("look", List.copyOf(look));
        for (var motion : PetPresentation.Motion.values()) {
            if (!animations.containsKey(motion.assetKey())) throw new JSONException("Missing motion: " + motion);
        }
        return new PetSpriteCatalog(width, height, animations);
    }

    private static String frameFile(JSONObject frame) throws JSONException {
        String file = frame.getString("file");
        if (!file.matches("frames/[a-z0-9_]+\\.png")) throw new JSONException("Invalid frame path");
        return file;
    }
}
