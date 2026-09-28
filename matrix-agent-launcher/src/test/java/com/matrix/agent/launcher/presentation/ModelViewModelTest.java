package com.matrix.agent.launcher.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelRuntimeStatus;

import org.junit.Test;

import java.util.List;

public final class ModelViewModelTest {
    @Test
    public void connectionProbeTargetsTheActiveCloudModel() {
        ModelInfo active = new ModelInfo("glm-5.3", "GLM", "glm_anthropic", true, true);
        ModelInfo installed = new ModelInfo("Qwen3", "Qwen3", "on_device", false, true);
        ModelViewModel.State state = new ModelViewModel.State(
                new ModelRuntimeStatus("glm-5.3", true, ModelRuntimeStatus.BACKEND_CLOUD, 0),
                List.of(installed, active), false, ModelViewModel.Notice.RUNTIME, 0);

        assertEquals("glm_anthropic", ModelViewModel.activeCloudModel(state).providerId);
    }

    @Test
    public void connectionProbeDoesNotMistakeAnEditedCloudFormForAnActiveModel() {
        ModelInfo active = new ModelInfo("Qwen3", "Qwen3", "on_device", true, true);
        ModelViewModel.State state = new ModelViewModel.State(
                new ModelRuntimeStatus("Qwen3", true, ModelRuntimeStatus.BACKEND_ON_DEVICE, 0),
                List.of(active), false, ModelViewModel.Notice.RUNTIME, 0);

        assertNull(ModelViewModel.activeCloudModel(state));
    }
}
