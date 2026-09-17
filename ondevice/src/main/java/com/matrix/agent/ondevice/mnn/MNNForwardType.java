package com.matrix.agent.ondevice.mnn;

/**
 * MNN 推理后端类型（与 MNN 上游 forward type 对齐）。
 *
 * <p>CPU-only 首发：native 仅编入 CPU 后端，故 UI 只应暴露 {@link #FORWARD_CPU}（+ {@link #FORWARD_AUTO}
 * 在 CPU-only 下等价 CPU）。GPU 后端（OPENCL=3 / OPENGL=6 / VULKAN=7）<b>仅在 native 真编入时才暴露</b>——
 * 当前 CMake 显式 OFF FORCE 了所有 GPU，故不暴露，避免 UI 给出无效选项。
 */
public enum MNNForwardType {
    FORWARD_CPU(0),
    FORWARD_AUTO(4);
    // FORWARD_OPENCL(3), FORWARD_OPENGL(6), FORWARD_VULKAN(7) —— CPU-only 不暴露

    public final int type;

    MNNForwardType(int type) {
        this.type = type;
    }
}
