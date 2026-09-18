package com.matrix.agent.model;

import com.matrix.agent.contract.ApiProtocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Locks the Launcher-visible provider matrix to Host-supported protocols and usable endpoints. */
public final class ModelProviderPresetTest {
    @Test public void exposesCloudLanAndOnDeviceProviders() {
        Map<String, ModelProviderPreset> presets = new HashMap<>();
        for (ModelProviderPreset preset : ModelProviderPreset.all()) presets.put(preset.id, preset);

        for (String id : new String[] {"glm", "deepseek", "qwen", "kimi", "doubao",
                "anthropic", "gemini", "ollama", "lmstudio", "vllm", "custom", "ondevice"}) {
            assertTrue("missing provider " + id, presets.containsKey(id));
        }
        assertEquals(ApiProtocol.ON_DEVICE, presets.get("ondevice").protocol);
        assertEquals(ApiProtocol.OLLAMA_CHAT, presets.get("ollama").protocol);
    }

    @Test public void openAiCompatibleLanPresetsUseConcreteCompletionRoutes() {
        assertTrue(find("lmstudio").endpoint.endsWith("/v1/chat/completions"));
        assertTrue(find("vllm").endpoint.endsWith("/v1/chat/completions"));
        assertTrue(find("ollama").endpoint.endsWith("/api/chat"));
    }

    private static ModelProviderPreset find(String id) {
        for (ModelProviderPreset preset : ModelProviderPreset.all()) if (id.equals(preset.id)) return preset;
        throw new AssertionError("missing provider " + id);
    }
}
