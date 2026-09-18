package com.matrix.agent;
import com.matrix.agent.host.di.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.platform.AndroidKeyStoreMasterKeyProvider;
import com.matrix.agent.platform.MasterKeyProvider;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;

/**
 * androidTest smoke —— APK 装到 emulator 后第一次端到端验证
 * SQLCipher + AndroidKeyStore 链路。
 *
 * <p>3 个 case 不依赖 AppContainer 自动装配(测试 Application 是默认 android.app.Application),
 * 直接构造 {@link AndroidKeyStoreMasterKeyProvider} + {@link MatrixDatabase} 触发链路。
 *
 * <p>注意:MatrixDatabase 单例在进程内共享,本测试 case 间复用同一实例(SQLCipher 链路验证
 * 一次成功即可)。
 */
@RunWith(AndroidJUnit4.class)
public final class SmokeTest {
    @Test
    public void appContextPackageNameCorrect() {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("com.matrix.agent", context.getPackageName());
    }

    @Test
    public void keyStoreMasterKeyAliasCreated() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        // 触发 provider.getPassphrase() 走 KeyStore 创建路径
        MasterKeyProvider provider = new AndroidKeyStoreMasterKeyProvider(context);
        char[] passphrase = provider.getPassphrase();
        assertNotNull("passphrase 不能为空", passphrase);
        assertTrue("passphrase 长度必须 > 0", passphrase.length > 0);

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        assertTrue("matrix_db_master_key alias 必须在 Keystore 内",
                keyStore.containsAlias(AndroidKeyStoreMasterKeyProvider.KEY_ALIAS));
    }

    @Test
    public void matrixDatabaseOpensWithKeystorePassphrase() {
        Context context = ApplicationProvider.getApplicationContext();
        MasterKeyProvider provider = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db = MatrixDatabase.getInstance(context, provider);
        assertNotNull("MatrixDatabase 必须成功初始化", db);
        // Room 建库是惰性的；先实际执行只读 SQL，再断言连接已打开。
        // 仅取得 DAO 不会触发 SQLCipher open，不能把该惰性行为误判为数据库已关闭。
        assertNotNull("trajectoryDao 必须可用", db.trajectoryDao());
        assertNotNull("sessionHistoryDao 必须可用", db.sessionHistoryDao());
        assertNotNull("memoryRecordDao 必须可用", db.memoryRecordDao());
        assertNotNull("空查询也必须可执行", db.sessionHistoryDao().queryByUserZone(
                "__smoke__", "__smoke__", 1));
        assertTrue("执行 SQL 后 db 应保持打开", db.isOpen());
    }
}
