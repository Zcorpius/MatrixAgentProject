package com.matrix.agent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.ondevice.mnn.MNNLibraryLoader;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

/**
 * 阶段 0 设备验证：端侧 MNN native 库能在 arm64 设备上 {@code System.loadLibrary} 成功
 * （无 {@link UnsatisfiedLinkError}），证明 libMNN.so + libMNNWrapper.so 的 ABI、ELF 格式、
 * JNI 入口符号在真机/模拟器加载链路上正确。
 *
 * <p>JNI 函数名绑定（按名字 {@code Java_com_matrix_agent_ondevice_mnn_MNNLlmNative_*}）的验证
 * 留到阶段 1 —— 写出 {@code MNNLlmNative} 并首次调用 native 方法时自然覆盖。
 */
@RunWith(AndroidJUnit4.class)
public class OnDeviceNativeLoadTest {

    @Test
    public void mnnNativeLibrariesLoad() {
        // 抛 UnsatisfiedLinkError 即测试失败（ABI 不匹配 / .so 损坏 / 库名错）
        MNNLibraryLoader.loadLibraries();
        assertTrue("MNN native libs should be loaded", MNNLibraryLoader.isLoaded());
    }
}
