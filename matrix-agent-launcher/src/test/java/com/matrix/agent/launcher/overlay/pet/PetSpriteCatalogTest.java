package com.matrix.agent.launcher.overlay.pet;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class PetSpriteCatalogTest {
    private final Path assets = Path.of("src/main/assets/yukino");
    private String bundled() throws Exception {
        return new String(Files.readAllBytes(assets.resolve("animations.json")), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test public void bundledCatalogTimingsAndPngDimensionsAgree() throws Exception {
        var catalog = PetSpriteCatalog.parse(bundled());
        assertEquals(10, catalog.animations().size()); // Nine actions plus neutral.
        assertEquals(58, catalog.animations().values().stream().mapToInt(java.util.List::size).sum());
        assertEquals(1100, new SpriteTimeline(catalog.animations().get("idle").stream()
                .map(PetSpriteCatalog.Frame::durationMs).toList()).durationMs());
        for (var frames : catalog.animations().values()) for (var frame : frames) {
            byte[] png = Files.readAllBytes(assets.resolve(frame.file()));
            var header = ByteBuffer.wrap(png);
            assertEquals(0x89504e470d0a1a0aL, header.getLong());
            assertEquals(catalog.width(), header.getInt(16));
            assertEquals(catalog.height(), header.getInt(20));
        }
        assertEquals("frames/neutral.png", catalog.animations().get("neutral").get(0).file());
    }

    @Test public void rejectsInvalidDurationsAndMissingRequiredActions() throws Exception {
        var json = new JSONObject(bundled());
        json.getJSONObject("animations").getJSONObject("idle").getJSONArray("frames")
                .getJSONObject(0).put("durationMs", 0);
        assertThrows(JSONException.class, () -> PetSpriteCatalog.parse(json.toString()));
        var missing = new JSONObject(bundled());
        missing.getJSONObject("animations").remove("running");
        assertThrows(JSONException.class, () -> PetSpriteCatalog.parse(missing.toString()));
    }

    @Test public void rejectsUnexpectedPathsAndUnsupportedSchema() throws Exception {
        var json = new JSONObject(bundled());
        json.getJSONObject("neutral").put("file", "../outside.png");
        assertThrows(JSONException.class, () -> PetSpriteCatalog.parse(json.toString()));
        var future = new JSONObject(bundled()).put("schemaVersion", 2);
        assertThrows(JSONException.class, () -> PetSpriteCatalog.parse(future.toString()));
    }
}
