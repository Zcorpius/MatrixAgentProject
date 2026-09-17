package com.matrix.agent.ondevice.mnn;

/**
 * 加载 MNN native 库（{@code libMNN.so} + {@code libMNNWrapper.so}）。
 *
 * <p>顺序固定：先 {@code MNN} 后 {@code MNNWrapper}（后者链接依赖前者）。
 * double-checked locking 保证进程内只加载一次；{@link UnsatisfiedLinkError} 直接抛给调用方。
 *
 * <p>阶段 0 用于设备验证 {@code System.loadLibrary} 可成功（ABI/格式/JNI 入口正确）；
 * 阶段 1 起 {@code MNNLlmNative} 的 {@code static {}} 块会调用 {@link #loadLibraries()}。
 */
public final class MNNLibraryLoader {

    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();

    private MNNLibraryLoader() {}

    public static void loadLibraries() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            System.loadLibrary("MNN");
            System.loadLibrary("MNNWrapper");
            loaded = true;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
