package com.matrix.agent;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.matrix.agent.ondevice.GenerationResult;
import com.matrix.agent.ondevice.MnnLoadOptions;
import com.matrix.agent.ondevice.OnDeviceLlm;
import com.matrix.agent.ondevice.mnn.MnnOnDeviceLlmFactory;

import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端侧推理认证：OnDeviceLlm 真实推理（Qwen3-0.6B-MNN）。
 *
 * <p>这是<strong>认证门禁</strong>而非普通 instrumentation 前置。常规
 * {@code connectedDebugAndroidTest} 会跳过本测试，避免没有预置数 GB 模型时把代码回归
 * 误报成失败；{@code connectedOnDeviceCertificationAndroidTest} 传入
 * {@code onDeviceCert=true} 后会强制执行，模型缺失即失败。
 *
 * <p>认证前置：模型 4 个文件已 push 到 {@code /data/local/tmp/qwen3-0.6b-mnn/}。
 * 验证 native 推理链路（load → generate）+ Qwen3 tool call 输出格式（{@code <tool_call>}）。
 * 不经过 gateway/codec/parser（那些已由 JVM 单测覆盖）；本测试聚焦 native + 封装层。
 */
@RunWith(AndroidJUnit4.class)
public class OnDeviceInferenceTest {

    private static final String MODEL_NAME = "Qwen3-0.6B-MNN";

    @Test
    public void loadsModelAndGeneratesToolCall() throws Exception {
        Assume.assumeTrue(
                "端侧推理认证需执行 :matrix-agent-service:connectedOnDeviceCertificationAndroidTest"
                        + "（并预置 Qwen3-0.6B-MNN 模型）",
                "true".equals(InstrumentationRegistry.getArguments().getString("onDeviceCert")));
        // 模型 push 到 /data/local/tmp（模拟器 production build 不可 adb root；
        // androidTest 进程是 app uid，可读 /data/local/tmp 子目录的 o+r 文件，不必 cp 到 filesDir）
        File modelDir = new File("/data/local/tmp/qwen3-0.6b-mnn");
        assertTrue("模型未 push 到 " + modelDir + "（adb push 4 文件）",
                new File(modelDir, "llm_config.json").exists());
        assertTrue("缺 llm.mnn.weight", new File(modelDir, "llm.mnn.weight").exists());

        OnDeviceLlm llm = new MnnOnDeviceLlmFactory().create(
                modelDir.getAbsolutePath(), MnnLoadOptions.cpuDefaults());
        assertTrue("模型应加载就绪", llm.isReady());
        assertTrue("max_all_tokens 应 >0（默认2048）", llm.maxAllTokens() > 0);
        Log.i("OnDeviceTest", "[load] maxAllTokens=" + llm.maxAllTokens());

        String messagesJson = "[{\"role\":\"system\",\"content\":\"你是车机助手，可通过工具控制车辆。\"},"
                + "{\"role\":\"user\",\"content\":\"把主驾空调温度调到24度\"}]";
        String toolsJson = "[{\"type\":\"function\",\"function\":{\"name\":\"climate_set\","
                + "\"description\":\"设置指定座位的空调温度\","
                + "\"parameters\":{\"type\":\"object\","
                + "\"properties\":{"
                + "\"zone\":{\"type\":\"string\",\"description\":\"座位:driver/passenger\"},"
                + "\"temp\":{\"type\":\"integer\",\"description\":\"温度(摄氏度)\"}},"
                + "\"required\":[\"zone\",\"temp\"]}}}]";

        GenerationResult r = llm.generate(messagesJson, toolsJson, 1024, () -> false);
        assertNotNull(r);
        Log.i("OnDeviceTest", "[gen] finishReason=" + r.finishReason
                + " genTokens=" + r.generatedTokens
                + " prefillMs=" + (r.prefillUs / 1000)
                + " decodeMs=" + (r.decodeUs / 1000)
                + " textLen=" + (r.text == null ? 0 : r.text.length()));

        assertNotNull("生成文本不应为 null", r.text);
        assertTrue("生成文本不应为空（finishReason=" + r.finishReason + " nativeError=" + r.nativeError + ")",
                !r.text.trim().isEmpty() || r.finishReason != null);

        // 期望产出 tool call（Qwen3 <tool_call> 格式，含 climate_set）
        boolean hasToolCall = r.text.contains("<tool_call>") || r.text.contains("climate_set");
        String preview = r.text.length() > 600 ? r.text.substring(0, 600) + "..." : r.text;
        Log.i("OnDeviceTest", "[output] " + preview);
        assertTrue("模型应产出 tool call（climate_set）。实际: " + preview, hasToolCall);
    }
}
