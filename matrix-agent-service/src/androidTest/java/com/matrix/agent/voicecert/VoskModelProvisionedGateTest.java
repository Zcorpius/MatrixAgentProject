package com.matrix.agent.voicecert;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.voice.ModelPathResolver;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertTrue;

/**
 * Vosk 模型预置<b>专项门禁</b>(voicecert 包):认证任务中模型缺失时失败(不 skip)。
 *
 * <p>与常规 {@code connectedDebugAndroidTest} 隔离(见 {@link VoiceCertification}):常规任务本测试
 * Assume 跳过(无模型环境保持全绿);{@code connectedVoiceCertificationAndroidTest} 放行后,
 * 预置错误(路径/布局/缺语言)必须在这里红出来,而不是让整套 Vosk 验证静默变绿。
 * 模型预置方式(应用内下载或 run-as 导入)见 {@link VoiceCertification} 类注释。
 * 兼容版本目录布局(active 版本子目录)与平铺布局,两语言缺一即失败。
 */
@RunWith(AndroidJUnit4.class)
public class VoskModelProvisionedGateTest {
    private static final File MODELS =
            new File(ApplicationProvider.getApplicationContext().getFilesDir(), "vosk-model");

    /** 与 VoskRecognizeTest 相同的解析规则:版本布局 active 目录优先,平铺 fallback。 */
    private static File resolveModelDir(String lang) {
        File langDir = new File(MODELS, lang);
        File active = ModelPathResolver.activeDir(langDir);
        return active != null ? active : langDir;
    }

    @Test
    public void bothModels_provisioned_gate() {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        File en = resolveModelDir("en");
        File cn = resolveModelDir("cn");
        assertTrue("en 唤醒模型未预置(" + en + ")——按 VoiceCertification 注释预置后重跑,缺失不得静默跳过",
                en != null && en.isDirectory());
        assertTrue("cn 识别模型未预置(" + cn + ")——按 VoiceCertification 注释预置后重跑,缺失不得静默跳过",
                cn != null && cn.isDirectory());
        assertTrue("en 模型目录为空:" + en, en.list() != null && en.list().length > 0);
        assertTrue("cn 模型目录为空:" + cn, cn.list() != null && cn.list().length > 0);
    }
}
