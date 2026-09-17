package com.matrix.agent.data.db;

import com.matrix.agent.platform.MasterKeyProvider;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * KeyStore 失败时,MasterKeyProvider
 * 必须抛 IllegalStateException 而非 return null——否则 MatrixDatabase 会构建明文 Room DB。
 *
 * <p>本测试用 fake MasterKeyProvider 模拟 getPassphrase() 抛异常 + 返回 null + 返回空数组
 * 三种场景,验证 MatrixDatabase.getInstance 全部抛 IllegalStateException。
 *
 * <p>真 AndroidKeyStore 失败链路靠 APK 手动验证(adb logcat 看 "fallback to Noop");
 * 本测试覆盖契约层面。
 */
public final class MatrixDatabaseKeyFailureContractTest {

    @After
    public void tearDown() {
        // 单例清理——防止下一个测试看到 stale instance
        MatrixDatabase.resetForTest();
    }

    @Test
    public void keyProviderNullThrows() {
        try {
            MatrixDatabase.getInstance(null, null);
            fail("keyProvider=null 必须抛异常");
        } catch (IllegalStateException expected) {
            assertEquals("MatrixDatabase init failed: keyProvider 为空", expected.getMessage());
        }
    }

    @Test
    public void passphraseNullThrows() {
        MasterKeyProvider nullProvider = new StubKeyProvider("stub-null", null);
        try {
            // context=null 不会到 Room.databaseBuilder(因为 passphrase 检查在前)
            MatrixDatabase.getInstance(null, nullProvider);
            fail("passphrase=null 必须抛异常");
        } catch (IllegalStateException expected) {
            assertEquals("MatrixDatabase init failed: passphrase 为空(Keystore 异常)",
                    expected.getMessage());
        }
    }

    @Test
    public void passphraseEmptyThrows() {
        MasterKeyProvider emptyProvider = new StubKeyProvider("stub-empty", new char[0]);
        try {
            MatrixDatabase.getInstance(null, emptyProvider);
            fail("passphrase length=0 必须抛异常");
        } catch (IllegalStateException expected) {
            assertEquals("MatrixDatabase init failed: passphrase 为空(Keystore 异常)",
                    expected.getMessage());
        }
    }

    @Test
    public void passphraseThrowingProviderPropagatesAsIllegalState() {
        MasterKeyProvider throwingProvider = new MasterKeyProvider() {
            @Override
            public char[] getPassphrase() {
                throw new IllegalStateException("Keystore uninitialized");
            }

            @Override
            public String alias() {
                return "stub-throwing";
            }
        };
        try {
            MatrixDatabase.getInstance(null, throwingProvider);
            fail("getPassphrase 抛异常必须向上传播");
        } catch (IllegalStateException expected) {
            // AndroidKeyStoreMasterKeyProvider.getPassphrase 把 Keystore
            // 异常包装为 IllegalStateException;fake 实现直接抛 IllegalStateException。
            // 两种路径 MatrixDatabase 都不 catch 在 passphrase 检查前(异常直接向上传播,
            // AuditRuntimeGraph 兜底退化为 NoopAuditRepository)。
        }
    }

    private static final class StubKeyProvider implements MasterKeyProvider {
        private final String alias;
        private final char[] passphrase;

        StubKeyProvider(String alias, char[] passphrase) {
            this.alias = alias;
            this.passphrase = passphrase;
        }

        @Override
        public char[] getPassphrase() {
            return passphrase;
        }

        @Override
        public String alias() {
            return alias;
        }
    }
}
