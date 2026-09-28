package com.matrix.agent.launcher.overlay.pet;

import static org.junit.Assert.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion.IDLE;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.launcher.LauncherApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Decodes each bundled character through the production repository on the connected device. */
@RunWith(AndroidJUnit4.class)
public final class PetCharacterAssetsDeviceTest {
    @Test public void allBundledCharactersDecodeIdleFramesOnDevice() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var app = (LauncherApplication) instrumentation.getTargetContext().getApplicationContext();
        for (PetCharacter character : PetCharacter.values()) {
            CompletableFuture<PetSpriteRepository.Result> decoded = new CompletableFuture<>();
            instrumentation.runOnMainSync(() -> app.petSprites().load(character, IDLE, decoded::complete));
            var result = decoded.get(15, TimeUnit.SECONDS);
            assertNull(character + " decode failure", result.failure());
            assertNotNull(character + " clip", result.clip());
            assertFalse(character + " idle frames", result.clip().frames().isEmpty());
            assertEquals(192, result.clip().frames().get(0).getWidth());
            assertEquals(208, result.clip().frames().get(0).getHeight());
        }
    }
}
