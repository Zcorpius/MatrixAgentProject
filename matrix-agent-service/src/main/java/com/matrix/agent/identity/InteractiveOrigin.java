package com.matrix.agent.identity;

/** Host-captured caller and original user message. Attachments/model/tool output cannot replace it. */
public record InteractiveOrigin(int uid, int androidUserId, String packageName, String userText) {
    public InteractiveOrigin {
        if (uid < 0 || androidUserId < 0 || packageName == null || packageName.isEmpty()
                || userText == null || userText.length() > 4096) throw new IllegalArgumentException("invalid interactive origin");
    }
}
