package com.matrix.agent.platform.media;

/** Dispatches a fixed public app entry point only when the Host may launch activities. */
public interface AppLaunchPort {
    void openApp(MediaApp app) throws MediaPlatformException;
    void openBilibiliVideo(String bvid, Integer page) throws MediaPlatformException;
}
