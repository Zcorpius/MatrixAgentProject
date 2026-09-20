package com.matrix.agent.ondevice.sherpa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * {@link SherpaAsrEngine#buildConfig} 装配校验：endpoint 规则（Operit 实测参数）、
 * 模型路径注入与解码策略。纯 JVM——不触及 OnlineRecognizer 类初始化（native 加载）。
 */
public final class SherpaAsrEngineConfigTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void buildsConfigWithOperitEndpointRulesAndModelPaths() throws IOException {
        SherpaModelFiles files = newModelFiles("enc.onnx", "dec.onnx", "join.onnx", "tokens.txt");

        OnlineRecognizerConfig config = SherpaAsrEngine.buildConfig(files, 2);

        assertEquals(2.4f, config.getEndpointConfig().getRule1().getMinTrailingSilence(), 0f);
        assertEquals(false, config.getEndpointConfig().getRule1().getMustContainNonSilence());
        assertEquals(1.2f, config.getEndpointConfig().getRule2().getMinTrailingSilence(), 0f);
        assertEquals(true, config.getEndpointConfig().getRule2().getMustContainNonSilence());
        assertEquals(20f, config.getEndpointConfig().getRule3().getMinUtteranceLength(), 0f);
        assertTrue(config.getEnableEndpoint());

        assertEquals(2, config.getModelConfig().getNumThreads());
        assertEquals(files.encoder.getAbsolutePath(),
                config.getModelConfig().getTransducer().getEncoder());
        assertEquals(files.decoder.getAbsolutePath(),
                config.getModelConfig().getTransducer().getDecoder());
        assertEquals(files.joiner.getAbsolutePath(),
                config.getModelConfig().getTransducer().getJoiner());
        assertEquals(files.tokens.getAbsolutePath(), config.getModelConfig().getTokens());
        assertEquals("modified_beam_search", config.getDecodingMethod());
        assertEquals(4, config.getMaxActivePaths());
    }

    @Test
    public void modelFilesValidationReportsEveryMissingFile() {
        File missingDir = new File(folder.getRoot(), "absent");
        try {
            new SherpaModelFiles(new File(missingDir, "e.onnx"), new File(missingDir, "d.onnx"),
                    new File(missingDir, "j.onnx"), new File(missingDir, "t.txt"));
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("e.onnx"));
            assertTrue(expected.getMessage().contains("d.onnx"));
            assertTrue(expected.getMessage().contains("j.onnx"));
            assertTrue(expected.getMessage().contains("t.txt"));
        }
    }

    private SherpaModelFiles newModelFiles(String... names) throws IOException {
        File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = folder.newFile(names[i]);
            try (FileOutputStream out = new FileOutputStream(files[i])) {
                out.write(1);
            }
        }
        return new SherpaModelFiles(files[0], files[1], files[2], files[3]);
    }
}
