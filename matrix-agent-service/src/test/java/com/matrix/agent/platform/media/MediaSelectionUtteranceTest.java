package com.matrix.agent.platform.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaSelectionUtteranceTest {
    @Test public void acceptsStandaloneAffirmation() {
        assertTrue(MediaSelectionUtterance.isAffirmative("是，播放吧"));
        assertTrue(MediaSelectionUtterance.isAffirmative("确认播放"));
        assertTrue(MediaSelectionUtterance.isAffirmative("Yes"));
    }

    @Test public void doesNotTreatNegationOrAnotherRequestAsConsent() {
        assertFalse(MediaSelectionUtterance.isAffirmative("不要播放"));
        assertFalse(MediaSelectionUtterance.isAffirmative("播放另一首"));
        assertTrue(MediaSelectionUtterance.isConfirmationReply("不要播放"));
    }
}
