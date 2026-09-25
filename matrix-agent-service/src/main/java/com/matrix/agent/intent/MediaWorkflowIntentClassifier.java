package com.matrix.agent.intent;

import com.matrix.agent.platform.media.MediaSelectionUtterance;

import java.util.Objects;
import java.util.regex.Pattern;

/** Avoids a remote classification call for unmistakable song commands and short confirmations. */
public final class MediaWorkflowIntentClassifier implements IntentClassifier {
    private static final Pattern SPECIFIC_SONG = Pattern.compile(
            "^\\s*(?:请|帮我)?\\s*(?:播放|放|听)\\s*"
                    + "[\\p{IsHan}A-Za-z0-9]{2,24}的.{1,48}\\s*$");

    private final IntentClassifier delegate;

    public MediaWorkflowIntentClassifier(IntentClassifier delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public IntentResult classify(String command) {
        if (command != null && (SPECIFIC_SONG.matcher(command).matches()
                || MediaSelectionUtterance.isConfirmationReply(command)
                || MediaSelectionUtterance.indexChoice(command) > 0)) {
            return IntentResult.write("explicit-media-workflow");
        }
        return delegate.classify(command);
    }
}
