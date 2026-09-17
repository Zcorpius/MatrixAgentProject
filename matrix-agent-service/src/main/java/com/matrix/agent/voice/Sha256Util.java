package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 文件哈希工具(纯 Java,无 Android 依赖,JVM 可测)。
 *
 * <p>用于模型下载完整性校验:zip 下载后比对其 SHA-256 与清单期望值,不匹配绝不解压/加载
 * (文档 §5.6「篡改 zip 或 hash 不匹配时绝不加载」)。
 */
public final class Sha256Util {

    private Sha256Util() { }

    /** 计算文件 SHA-256 的十六进制字符串(小写)。空文件返回其固定哈希。 */
    public static String sha256Hex(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
