package com.matrix.agent.ondevice.sherpa;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式 zipformer transducer 模型文件四元组（ASR 与 KWS 共用同一文件布局）。
 *
 * <p>构造即校验四个文件全部存在（fail-fast，缺失清单一次报全），
 * 引擎构造器只接收已验证的文件集——不存在"半套模型"进 native 的窗口。</p>
 */
public final class SherpaModelFiles {

    /** encoder onnx（int8 量化）。 */
    public final File encoder;
    /** decoder onnx。 */
    public final File decoder;
    /** joiner onnx。 */
    public final File joiner;
    /** 词表（token → id）。 */
    public final File tokens;

    public SherpaModelFiles(File encoder, File decoder, File joiner, File tokens) {
        List<String> missing = new ArrayList<>();
        if (encoder == null || !encoder.isFile()) missing.add(name(encoder));
        if (decoder == null || !decoder.isFile()) missing.add(name(decoder));
        if (joiner == null || !joiner.isFile()) missing.add(name(joiner));
        if (tokens == null || !tokens.isFile()) missing.add(name(tokens));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Sherpa 模型文件缺失: " + String.join(", ", missing));
        }
        this.encoder = encoder;
        this.decoder = decoder;
        this.joiner = joiner;
        this.tokens = tokens;
    }

    private static String name(File f) {
        return f == null ? "(null)" : f.getName();
    }
}
