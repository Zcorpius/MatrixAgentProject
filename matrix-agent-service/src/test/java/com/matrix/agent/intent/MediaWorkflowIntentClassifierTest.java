package com.matrix.agent.intent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaWorkflowIntentClassifierTest {
    @Test public void specificSongAndConfirmationSkipRemoteClassifier() {
        MediaWorkflowIntentClassifier classifier = new MediaWorkflowIntentClassifier(
                command -> { throw new AssertionError("delegate must not run"); });
        assertFalse(classifier.isReadOnly("播放李健的传奇"));
        assertFalse(classifier.isReadOnly("是，播放吧"));
        assertFalse(classifier.isReadOnly("不要播放"));
        assertFalse(classifier.isReadOnly("第 2 首"));
    }

    @Test public void unrelatedRequestStillUsesDelegate() {
        MediaWorkflowIntentClassifier classifier = new MediaWorkflowIntentClassifier(
                command -> IntentResult.readOnly("delegate"));
        assertTrue(classifier.isReadOnly("查询电量"));
    }
}
