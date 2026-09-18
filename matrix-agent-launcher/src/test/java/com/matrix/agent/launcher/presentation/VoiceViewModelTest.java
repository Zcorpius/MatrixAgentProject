package com.matrix.agent.launcher.presentation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Regression tests for duplicate terminal callbacks observed on a real Android 15 device. */
public final class VoiceViewModelTest {
    @Test
    public void terminalPhase_keepsInterruptedAcrossDuplicateIdle() {
        assertEquals(VoiceViewModel.Phase.INTERRUPTED,
                VoiceViewModel.terminalPhase(VoiceViewModel.Phase.INTERRUPTING));
        assertEquals(VoiceViewModel.Phase.INTERRUPTED,
                VoiceViewModel.terminalPhase(VoiceViewModel.Phase.INTERRUPTED));
    }

    @Test
    public void terminalPhase_keepsFinishedAndErrorAcrossDuplicateIdle() {
        assertEquals(VoiceViewModel.Phase.FINISHED,
                VoiceViewModel.terminalPhase(VoiceViewModel.Phase.FINISHING));
        assertEquals(VoiceViewModel.Phase.FINISHED,
                VoiceViewModel.terminalPhase(VoiceViewModel.Phase.FINISHED));
        assertEquals(VoiceViewModel.Phase.ERROR,
                VoiceViewModel.terminalPhase(VoiceViewModel.Phase.ERROR));
    }
}
