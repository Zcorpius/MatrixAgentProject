package com.matrix.agent.platform;

/**
 * SQLCipher 主密钥提供者——给 MatrixDatabase 注入 passphrase。
 *
 * <p>实现策略:
 * <ul>
 *   <li>{@link AndroidKeyStoreMasterKeyProvider} —— APK 主路径,
 *       复用 SecureModelConfigStore 模式(AndroidKeyStore AES-GCM 加密 32 字节随机 passphrase)。</li>
 * </ul>
 *
 * <p>测试通过 fake 实现注入,不依赖 AndroidKeyStore(JVM 单测无法访问)。
 */
public interface MasterKeyProvider {
    /** 返回 32 字节随机 passphrase 的 char[] 表示;null 表示未配置(退化不加密)。 */
    char[] getPassphrase();

    /** AndroidKeyStore 别名,用于日志 / 调试。 */
    String alias();
}
