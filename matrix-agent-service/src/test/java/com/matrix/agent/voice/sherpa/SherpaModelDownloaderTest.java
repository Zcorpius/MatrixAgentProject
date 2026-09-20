package com.matrix.agent.voice.sherpa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Sherpa 模型管道的纯文件逻辑：tar.bz2 白名单解压（顶层目录拍平 + 多精度变体剔除）、
 * RAW 单文件落位、tar-slip 防护。夹具用 commons-compress 现场构造，模拟上游
 * asr-models/kws-models 的发布结构。
 */
public final class SherpaModelDownloaderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void untarBz2ExtractsWhitelistOnlyAndFlattensTopDir() throws IOException {
        File archive = folder.newFile("model.tar.bz2");
        writeTarBz2(archive,
                "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/",
                entry("encoder-epoch-99-avg-1.int8.onnx", "int8-enc"),
                entry("encoder-epoch-99-avg-1.onnx", "fp32-enc"),
                entry("decoder-epoch-99-avg-1.int8.onnx", "int8-dec"),
                entry("joiner-epoch-99-avg-1.int8.onnx", "int8-join"),
                entry("tokens.txt", "tokens"),
                entry("test_wavs/0.wav", "wav"));

        File dest = folder.newFolder("versions");
        SherpaModelSpec spec = SherpaModelSpec.streamingBilingual(folder.getRoot());

        SherpaModelDownloader.untarBz2Flatten(archive, dest, spec, () -> false);

        assertEquals("int8-enc", read(new File(dest, "encoder-epoch-99-avg-1.int8.onnx")));
        assertEquals("int8-dec", read(new File(dest, "decoder-epoch-99-avg-1.int8.onnx")));
        assertEquals("int8-join", read(new File(dest, "joiner-epoch-99-avg-1.int8.onnx")));
        assertEquals("tokens", read(new File(dest, "tokens.txt")));
        // 白名单外：fp32 变体与测试音频不落盘
        assertFalse(new File(dest, "encoder-epoch-99-avg-1.onnx").exists());
        assertFalse(new File(dest, "test_wavs").exists());
        assertFalse(new File(dest, "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20")
                .exists());
    }

    @Test
    public void rawInstallPlacesDownloadedFileUnderRequiredName() throws IOException {
        File downloaded = folder.newFile("download.bin");
        Files.write(downloaded.toPath(), "vad-model".getBytes(StandardCharsets.UTF_8));
        File versionDir = folder.newFolder("versions");
        SherpaModelSpec spec = SherpaModelSpec.sileroVad(folder.getRoot());

        SherpaModelDownloader.installRaw(downloaded, versionDir, spec);

        assertEquals("vad-model", read(new File(versionDir, "silero_vad_v5.onnx")));
    }

    @Test
    public void secureResolveRejectsParentTraversal() throws IOException {
        File destDir = folder.newFolder("dest");
        SherpaModelSpec spec = SherpaModelSpec.sileroVad(folder.getRoot());
        try {
            SherpaModelDownloader.secureResolve(destDir, "../evil.onnx", spec);
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ARCHIVE_SLIP"));
        }
    }

    @Test
    public void specLayoutInvariantHoldsForTransducerSuites() {
        SherpaModelSpec asr = SherpaModelSpec.streamingBilingual(folder.getRoot());
        SherpaModelSpec kws = SherpaModelSpec.kwsZhEn(folder.getRoot());
        // requiredFiles 前 4 项 = encoder/decoder/joiner/tokens（装配层按序取用）
        assertTrue(asr.encoderName().startsWith("encoder"));
        assertTrue(asr.decoderName().startsWith("decoder"));
        assertTrue(asr.joinerName().startsWith("joiner"));
        assertEquals("tokens.txt", asr.tokensName());
        assertTrue(kws.encoderName().startsWith("encoder"));
        assertTrue(kws.decoderName().startsWith("decoder"));
        assertTrue(kws.joinerName().startsWith("joiner"));
        assertEquals("tokens.txt", kws.tokensName());
        // 三件套钉死值非空且自洽（RAW 单文件下载体积 = 安装体积）
        SherpaModelSpec vad = SherpaModelSpec.sileroVad(folder.getRoot());
        assertEquals(vad.sizeBytes, vad.installSizeBytes);
        assertFalse(asr.sha256.isEmpty());
        assertFalse(kws.sha256.isEmpty());
        assertFalse(vad.sha256.isEmpty());
    }

    @Test
    public void piperTtsSpecPinsEveryRuntimeFile() {
        SherpaModelSpec tts = SherpaModelSpec.piperZhCn(folder.getRoot());
        assertEquals(SherpaModelSpec.Kind.TTS, tts.kind);
        assertEquals(SherpaModelSpec.Format.TAR_BZ2, tts.format);
        assertEquals("zh_CN-xiao_ya-medium.onnx", tts.marker);
        assertEquals(60_462_944L, tts.sizeBytes);
        assertEquals(tts.sizeBytes, tts.installSizeBytes);
        assertFalse(tts.sha256.isEmpty());
        assertEquals(7, tts.requiredFiles.length);
        assertEquals("zh_CN-xiao_ya-medium.onnx", tts.requiredFiles[0]);
        assertEquals("number.fst", tts.requiredFiles[tts.requiredFiles.length - 1]);
    }

    @Test
    public void completeVerifiedArchiveSkipsAnotherNetworkRequest() throws IOException {
        File archive = folder.newFile("complete.tar.bz2");
        byte[] body = "ready".getBytes(StandardCharsets.UTF_8);
        Files.write(archive.toPath(), body);
        SherpaModelSpec spec = new SherpaModelSpec(SherpaModelSpec.Kind.TTS,
                SherpaModelSpec.Format.TAR_BZ2, "fixture", "https://example.invalid/model",
                folder.newFolder("model"), "model.onnx", "fixture", "1", body.length,
                body.length,
                "b24d6d33736ecd5604a4b17bc9c6481039fac362bb7df044ef1c10a2bfd21db6",
                new String[]{"model.onnx"});

        assertTrue(SherpaModelDownloader.isCompleteVerifiedArchive(spec, archive));
        assertTrue(archive.exists());
    }

    // ---------------------------------------------------------------- 夹具

    private static Entry entry(String name, String content) {
        return new Entry(name, content);
    }

    private static class Entry {
        final String name;
        final String content;

        Entry(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }

    private static void writeTarBz2(File target, String topDir, Entry... entries)
            throws IOException {
        try (OutputStream fos = new FileOutputStream(target);
             BufferedOutputStream buffered = new BufferedOutputStream(fos);
             BZip2CompressorOutputStream bz2 = new BZip2CompressorOutputStream(buffered);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(bz2)) {
            for (Entry entry : entries) {
                byte[] content = entry.content.getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry tarEntry = new TarArchiveEntry(topDir + entry.name);
                tarEntry.setSize(content.length);
                tar.putArchiveEntry(tarEntry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
