package com.matrix.agent.ondevice.mnn;

/**
 * MNN LLM JNI 桥（Java 侧）。
 *
 * <p>只声明端侧 gateway 用到的 native 方法——<b>Structured 系列</b>（传 JSON，绕开 cpp 里硬编码的
 * {@code kotlin/Pair} 依赖）+ 基础生命周期。带 history(Pair) 的 3 个函数已从 cpp 删除，故不声明。
 *
 * <p>static 块加载库（先 MNN 后 MNNWrapper）。JNI 按名字绑定，函数名前缀
 * {@code Java_com_matrix_agent_ondevice_mnn_MNNLlmNative_}（cpp 已同步）。
 */
public final class MNNLlmNative {

    static {
        MNNLibraryLoader.loadLibraries();
    }

    private MNNLlmNative() {}

    // —— 生命周期 ——
    public static native long nativeCreateLlm(String configPath);
    public static native boolean nativeLoadLlm(long llmPtr);
    public static native void nativeReleaseLlm(long llmPtr);
    public static native boolean nativeSetConfig(long llmPtr, String configJson);

    // —— Structured 推理（绕开 kotlin/Pair）——
    public static native boolean nativeGenerateStreamStructured(long llmPtr,
            String messagesJson, String toolsJson, int maxTokens, GenerationCallback callback);
    public static native String nativeApplyChatTemplateWithStructuredMessages(
            long llmPtr, String messagesJson, String toolsJson);
    public static native int nativeCountTokensWithStructuredMessages(
            long llmPtr, String messagesJson, String toolsJson);

    // —— 状态/控制 ——
    public static native void nativeReset(long llmPtr);
    public static native void nativeCancel(long llmPtr);
    public static native String nativeDumpConfig(long llmPtr);
    public static native String nativeGetContextInfo(long llmPtr);

    /**
     * 流式 token 回调。返回 {@code true} 继续生成，{@code false} 停止。
     * cpp 靠 {@code GetMethodID("onToken", "(Ljava/lang/String;)Z")} 查找——方法名与签名固定，不可改。
     */
    public interface GenerationCallback {
        boolean onToken(String token);
    }
}
