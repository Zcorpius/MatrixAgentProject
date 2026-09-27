package com.matrix.agent.platform.media;

/** Pure checks applied after reading the Bilibili accessibility tree. */
final class BilibiliResultRules {
    record Bounds(int left, int top, int right, int bottom) {
        boolean nonempty() { return left < right && top < bottom; }
    }

    private BilibiliResultRules() {}

    static boolean within(Bounds item, Bounds container) {
        return item.nonempty() && container.nonempty()
                && container.left() <= item.left() && container.top() <= item.top()
                && item.right() <= container.right() && item.bottom() <= container.bottom();
    }

    static boolean sameResult(BilibiliUiPort.Candidate left,
            BilibiliUiPort.Candidate right) {
        return left.title().equals(right.title()) && left.kind().equals(right.kind())
                && left.detail().equals(right.detail());
    }
}
