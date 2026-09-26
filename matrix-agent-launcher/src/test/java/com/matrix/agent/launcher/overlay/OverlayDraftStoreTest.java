package com.matrix.agent.launcher.overlay;
import static org.junit.Assert.*;
import org.junit.Test;
public final class OverlayDraftStoreTest {
    @Test public void successfulSubmissionCannotEraseNewTypingOrAnotherConversation() {
        var drafts = new OverlayDraftStore(); drafts.set("a", "first"); drafts.set("b", "other");
        long submitted = drafts.get("a").revision(); drafts.set("a", "new typing");
        assertFalse(drafts.clearIfRevision("a", submitted)); assertEquals("new typing", drafts.get("a").text());
        assertEquals("other", drafts.get("b").text());
        assertTrue(drafts.clearIfRevision("a", drafts.get("a").revision()));
    }
}
