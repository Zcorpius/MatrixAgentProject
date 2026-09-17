package com.matrix.agent.voicecert;

import androidx.test.platform.app.InstrumentationRegistry;

/**
 * 语音真机认证门禁的执行开关与预置说明。
 *
 * <p><b>隔离约定</b>:voicecert 包内的测试依赖模型预置/真实麦克风/中文 TTS 引擎/焦点抢占行为,
 * 与常规 {@code connectedDebugAndroidTest} 隔离——
 * <ul>
 *   <li>常规任务:本包测试经 {@link #enabled()} 判定后 {@code Assume} 跳过(保持默认任务可靠、无硬件前提全绿);</li>
 *   <li>{@code connectedVoiceCertificationAndroidTest}(app/build.gradle 定义):按包过滤 +
 *       {@code -e voiceCert true} 执行,此时门禁<strong>失败即红</strong>(模型缺失/硬件能力不足不遮蔽)。</li>
 * </ul>
 *
 * <p><b>模型预置</b>(二选一;非 root 设备不可直接 adb push 到 /data/data):
 * <ul>
 *   <li>应用内下载:直接启动 Demo(VoiceRuntime.start 自动下载 en/cn 到 files/vosk-model),零手工;</li>
 *   <li>run-as 导入(debuggable 包可用):
 * <pre>
 *   adb push vosk-model-small-en-us-0.15 /data/local/tmp/vosk-en
 *   adb push vosk-model-small-cn-0.22   /data/local/tmp/vosk-cn
 *   adb shell run-as com.matrix.agent mkdir -p files/vosk-model/en files/vosk-model/cn
 *   adb shell run-as com.matrix.agent cp -r /data/local/tmp/vosk-en/. files/vosk-model/en/
 *   adb shell run-as com.matrix.agent cp -r /data/local/tmp/vosk-cn/. files/vosk-model/cn/
 *   adb shell rm -rf /data/local/tmp/vosk-en /data/local/tmp/vosk-cn
 * </pre></li>
 * </ul>
 */
final class VoiceCertification {
    private VoiceCertification() { }

    /** 是否语音认证门禁执行(仅 connectedVoiceCertificationAndroidTest 传 voiceCert=true)。 */
    static boolean enabled() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("voiceCert"));
    }

    /** 各测试统一前置:常规任务跳过并说明,认证任务放行(断言硬失败)。 */
    static String skipReason() {
        return "语音认证门禁测试:请执行 ./gradlew :app:connectedVoiceCertificationAndroidTest(需模型预置/麦克风/TTS)";
    }
}
