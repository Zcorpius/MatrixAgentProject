package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.io.File;

/**
 * Vosk 模型下载规格。
 *
 * <p>描述一个 Vosk 模型的 zip URL、解压目标目录、完成标志文件,以及 modelId/版本/大小/SHA-256。
 * zip 解压后顶层目录被 flatten 到 {@code <targetDir>/versions/<version>/}(内容直接落在版本目录下,
 * 不带顶层目录名),经 {@link ModelPathResolver#promote} 提升为 active 后供 VoskModelHolder 加载。
 *
 * <p>{@link #sha256} 可空:非空时下载后校验(防篡改),空时跳过(框架就位,真机 shasum 算填入即启用)。
 */
public final class VoskModelSpec {
    /** 进度记录用的模型名(DAO 主键),如 {@code "vosk-en"}。 */
    public final String name;
    /** zip 下载地址。 */
    public final String zipUrl;
    /** 解压目标目录根(filesDir/vosk-model/{en,cn}),版本内容在其下 versions/<version>/。 */
    public final File targetDir;
    /** 完成标志文件(相对版本目录),如 {@code "conf/mfcc.conf"}。 */
    public final String marker;
    /** 真实模型 id,如 {@code "vosk-model-small-cn-0.22"}。 */
    public final String modelId;
    /** 模型版本,如 {@code "0.22"}(版本目录名 + active 指针值)。 */
    public final String version;
    /** zip 下载大小(字节,0 表示未知,用于下载进度)。 */
    public final long sizeBytes;
    /** 解压后大小(字节,0 表示未知,用于安装存储预检 + 解压上限防 zip bomb)。 */
    public final long installSizeBytes;
    /** SHA-256(可空):非空时下载后校验,空时跳过。 */
    public final String sha256;

    /** 4 参构造(向后兼容,modelId=name/version="0"/sizeBytes=installSizeBytes=0/sha256=null)。 */
    public VoskModelSpec(String name, String zipUrl, File targetDir, String marker) {
        this(name, zipUrl, targetDir, marker, name, "0", 0L, 0L, null);
    }

    /** 全参构造。 */
    public VoskModelSpec(String name, String zipUrl, File targetDir, String marker,
            String modelId, String version, long sizeBytes, long installSizeBytes, String sha256) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        if (zipUrl == null || zipUrl.isEmpty()) throw new IllegalArgumentException("zipUrl 不能为空");
        if (targetDir == null) throw new IllegalArgumentException("targetDir 不能为空");
        if (marker == null || marker.isEmpty()) throw new IllegalArgumentException("marker 不能为空");
        if (modelId == null || modelId.isEmpty()) throw new IllegalArgumentException("modelId 不能为空");
        if (version == null || version.isEmpty()) throw new IllegalArgumentException("version 不能为空");
        if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes 不能为负");
        if (installSizeBytes < 0) throw new IllegalArgumentException("installSizeBytes 不能为负");
        this.name = name;
        this.zipUrl = zipUrl;
        this.targetDir = targetDir;
        this.marker = marker;
        this.modelId = modelId;
        this.version = version;
        this.sizeBytes = sizeBytes;
        this.installSizeBytes = installSizeBytes;
        this.sha256 = sha256;
    }

    /**
     * 英文唤醒小模型(vosk-model-small-en-us-0.15)。
     * SHA-256/size 取自官方公开 zip 权威计算,下载后自动校验防篡改。
     */
    public static VoskModelSpec en(File voskRoot) {
        return new VoskModelSpec("vosk-en",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                new File(voskRoot, "en"), "conf/mfcc.conf",
                "vosk-model-small-en-us-0.15", "0.15",
                41205931L, 41205931L, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498");
    }

    /** 中文 ASR 小模型(vosk-model-small-cn-0.22)。SHA-256/size 同 en 取自官方公开 zip。 */
    public static VoskModelSpec cn(File voskRoot) {
        return new VoskModelSpec("vosk-cn",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                new File(voskRoot, "cn"), "conf/mfcc.conf",
                "vosk-model-small-cn-0.22", "0.22",
                43898754L, 43898754L, "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba");
    }
}
