package com.matrix.agent.platform.media;

/** Fixed app identities: model arguments never select a package. */
public enum MediaApp {
    QQMUSIC("qqmusic", "com.tencent.qqmusic"),
    BILIBILI("bilibili", "tv.danmaku.bili");

    private final String wireName;
    private final String packageName;

    MediaApp(String wireName, String packageName) {
        this.wireName = wireName;
        this.packageName = packageName;
    }

    public String wireName() { return wireName; }
    public String packageName() { return packageName; }
}
