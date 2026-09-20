package com.matrix.agent.voice.sherpa;

import java.io.File;

/**
 * Sherpa 语音模型规格（ASR / VAD / KWS / TTS，对应 VoskModelSpec 的 Sherpa 版）。
 *
 * <p>上游为 k2-fsa GitHub releases（无 Maven 化分发），URL + SHA-256 + 字节数全部钉死：
 * ASR/KWS 的 sha256 与上游 releases 的 checksum 体系对齐（kws-models 有官方 checksum.txt，
 * asr-models 按下载实测固化）。{@code requiredFiles} 兼三职：解压白名单（大包含多精度
 * 变体与测试音频，只落盘需要的一套）、安装完整性校验（缺一即 FAIL）、装配路径解析。</p>
 */
public final class SherpaModelSpec {

    /** 模型用途（安装 UI 分组 + 装配工厂路由）。 */
    public enum Kind { ASR, VAD, KWS, TTS }

    /** 上游发布格式：zip（Vosk 风格）/ tar.bz2（sherpa releases）/ 单文件（RAW）。 */
    public enum Format { ZIP, TAR_BZ2, RAW }

    public final Kind kind;
    public final Format format;
    /** 进度记录用模型名（DAO 主键）。 */
    public final String name;
    /** 下载地址。 */
    public final String url;
    /** 同一归档字节的受校验备用地址；空表示无备用源。 */
    public final String fallbackUrl;
    /** 安装目标根（如 filesDir/sherpa-model/zh-en）。 */
    public final File targetDir;
    /** 完成标志文件（相对版本目录）。 */
    public final String marker;
    /** 真实模型 id（展示用）。 */
    public final String modelId;
    /** 模型版本（版本目录名 + active 指针值）。 */
    public final String version;
    /** 下载字节数（进度预检；0 = 未知）。 */
    public final long sizeBytes;
    /** 安装后字节数（存储预检；requiredFiles 只数之和）。 */
    public final long installSizeBytes;
    /** SHA-256（小写 hex；非空时下载后校验）。 */
    public final String sha256;
    /** 解压白名单 + 完整性校验清单（相对版本目录；RAW 格式为目标文件名单元素）。 */
    public final String[] requiredFiles;

    public SherpaModelSpec(Kind kind, Format format, String name, String url, File targetDir,
            String marker, String modelId, String version, long sizeBytes, long installSizeBytes,
            String sha256, String[] requiredFiles) {
        this(kind, format, name, url, null, targetDir, marker, modelId, version, sizeBytes,
                installSizeBytes, sha256, requiredFiles);
    }

    public SherpaModelSpec(Kind kind, Format format, String name, String url, String fallbackUrl,
            File targetDir, String marker, String modelId, String version, long sizeBytes,
            long installSizeBytes, String sha256, String[] requiredFiles) {
        this.kind = kind;
        this.format = format;
        this.name = name;
        this.url = url;
        this.fallbackUrl = fallbackUrl;
        this.targetDir = targetDir;
        this.marker = marker;
        this.modelId = modelId;
        this.version = version;
        this.sizeBytes = sizeBytes;
        this.installSizeBytes = installSizeBytes;
        this.sha256 = sha256;
        this.requiredFiles = requiredFiles;
    }

    /**
     * 流式 transducer 套件（ASR/KWS）的布局不变式：requiredFiles 前 4 项固定为
     * encoder/decoder/joiner/tokens（装配层按序取用，文件名上游各不相同）。
     */
    public String encoderName() { return requiredFiles[0]; }

    public String decoderName() { return requiredFiles[1]; }

    public String joinerName() { return requiredFiles[2]; }

    public String tokensName() { return requiredFiles[3]; }

    /**
     * ASR：中英双语流式 zipformer（2023-02-20，int8 套件）——车控/对话场景首选，
     * 与 Operit 所选 ncnn 版本同源世代。上游包 511MB（含 fp32/int8 双精度 + 测试音频），
     * requiredFiles 白名单只落盘 int8 一套 ≈ 198MB。
     */
    public static SherpaModelSpec streamingBilingual(File sherpaRoot) {
        return new SherpaModelSpec(
                Kind.ASR,
                Format.TAR_BZ2,
                "sherpa-asr-zh-en",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
                new File(sherpaRoot, "asr-zh-en"),
                "tokens.txt",
                "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                "2023-02-20",
                511_274_346L,   // tar.bz2 实测
                198_270_793L,   // int8 encoder+decoder+joiner+tokens 实测之和
                "27ffbd9ee24ad186d99acc2f6354d7992b27bcab490812510665fa8f9389c5f8",
                new String[]{
                        "encoder-epoch-99-avg-1.int8.onnx",
                        "decoder-epoch-99-avg-1.int8.onnx",
                        "joiner-epoch-99-avg-1.int8.onnx",
                        "tokens.txt",
                });
    }

    /**
     * VAD：silero v5 单文件（2.3MB）。v4/v5 图由 sherpa 运行时自适配；
     * 缺省可降级为无门控全量喂识别（装配工厂处理），故为可选模型。
     */
    public static SherpaModelSpec sileroVad(File sherpaRoot) {
        return new SherpaModelSpec(
                Kind.VAD,
                Format.RAW,
                "sherpa-vad-silero",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
                        + "silero_vad_v5.onnx",
                new File(sherpaRoot, "vad-silero"),
                "silero_vad_v5.onnx",
                "silero_vad_v5.onnx",
                "v5",
                2_313_101L,
                2_313_101L,
                "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396",
                new String[]{"silero_vad_v5.onnx"});
    }

    /**
     * KWS：zh-en 3M 音素级关键词模型（2025-12-20，chunk-16 int8 套件 ≈ 8.7MB）。
     * 保留 en.phone（CMU 发音词典）供后续自定义英文唤醒词拼写。可选模型：
     * 未安装时唤醒不可用（PTT/手动入口不受影响）。
     */
    public static SherpaModelSpec kwsZhEn(File sherpaRoot) {
        return new SherpaModelSpec(
                Kind.KWS,
                Format.TAR_BZ2,
                "sherpa-kws-zh-en",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/"
                        + "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2",
                new File(sherpaRoot, "kws-zh-en"),
                "tokens.txt",
                "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20",
                "2025-12-20",
                32_885_699L,    // tar.bz2（官方 kws-models/checksum.txt 对齐）
                8_700_104L,     // chunk-16 int8 三件 + tokens + en.phone 实测之和
                "68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6",
                new String[]{
                        "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                        "decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
                        "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
                        "tokens.txt",
                        "en.phone",
                });
    }

    /**
     * 本地播报：Piper 中文“小雅”中等模型。
     *
     * <p>这是应用自带 Sherpa JNI 直接加载的 VITS/Piper 模型，不依赖设备有没有注册
     * {@code android.intent.action.TTS_SERVICE}。因此精简 ROM（本机正是此情况）仍可完成
     * 最终语音播报。该包只有模型、词典和文本归一化 FST；白名单逐项钉死，避免上游包布局
     * 变化后出现“显示已安装、实际播不出”的半安装状态。</p>
     */
    public static SherpaModelSpec piperZhCn(File sherpaRoot) {
        return new SherpaModelSpec(
                Kind.TTS,
                Format.TAR_BZ2,
                "sherpa-tts-zh-xiaoya",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
                        + "vits-piper-zh_CN-xiao_ya-medium.tar.bz2",
                // The China-facing Hugging Face mirror resolves to the same byte-pinned archive.
                // The downloader still admits it only after SHA-256 verification, so a mirror
                // outage or tampering cannot become a model installation.
                "https://hf-mirror.com/4-alokk/piper-voices/resolve/main/"
                        + "vits-piper-zh_CN-xiao_ya-medium.tar.bz2",
                new File(sherpaRoot, "tts-zh-xiaoya"),
                "zh_CN-xiao_ya-medium.onnx",
                "vits-piper-zh_CN-xiao_ya-medium",
                "2026-09",
                60_462_944L,
                60_462_944L,
                "9396a3dffbb95b037acaa18094500f58d0db9a7c4f2689554e2539717cf0db65",
                new String[]{
                        "zh_CN-xiao_ya-medium.onnx",
                        "zh_CN-xiao_ya-medium.onnx.json",
                        "tokens.txt",
                        "lexicon.txt",
                        "phone.fst",
                        "date.fst",
                        "number.fst",
                });
    }
}
