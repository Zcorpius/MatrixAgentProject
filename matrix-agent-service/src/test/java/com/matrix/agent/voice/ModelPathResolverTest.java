package com.matrix.agent.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@link ModelPathResolver} 版本目录 + active/previous 指针 + 清理。 */
public final class ModelPathResolverTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File target() {
        return new File(tmp.getRoot(), "en");
    }

    private void createVersion(File targetDir, String version) throws IOException {
        File vd = ModelPathResolver.versionDir(targetDir, version);
        if (!vd.exists() && !vd.mkdirs()) throw new IOException("无法创建 " + vd);
    }

    @Test
    public void noActive_returnsNull() {
        assertNull(ModelPathResolver.activeVersion(target()));
        assertNull(ModelPathResolver.activeDir(target()));
    }

    @Test
    public void promote_writesActive_noPreviousFirstTime() throws IOException {
        File t = target();
        createVersion(t, "0.15");
        ModelPathResolver.promote(t, "0.15");
        assertEquals("0.15", ModelPathResolver.activeVersion(t));
        assertEquals(ModelPathResolver.versionDir(t, "0.15"), ModelPathResolver.activeDir(t));
        assertNull("首次 promote 无 previous", ModelPathResolver.previousVersion(t));
    }

    @Test
    public void chainedPromote_previousTracksOldActive() throws IOException {
        File t = target();
        createVersion(t, "0.15");
        ModelPathResolver.promote(t, "0.15");
        createVersion(t, "0.16");
        ModelPathResolver.promote(t, "0.16");
        assertEquals("0.16", ModelPathResolver.activeVersion(t));
        assertEquals("0.15", ModelPathResolver.previousVersion(t));
        assertEquals(ModelPathResolver.versionDir(t, "0.16"), ModelPathResolver.activeDir(t));
        assertEquals(ModelPathResolver.versionDir(t, "0.15"), ModelPathResolver.previousDir(t));
    }

    @Test
    public void promoteCleansOlder_keepsActiveAndPrevious() throws IOException {
        File t = target();
        createVersion(t, "0.15");
        ModelPathResolver.promote(t, "0.15");
        createVersion(t, "0.16");
        ModelPathResolver.promote(t, "0.16");
        createVersion(t, "0.17");
        ModelPathResolver.promote(t, "0.17");
        // active=0.17, previous=0.16, 0.15 应被清理
        assertEquals("0.17", ModelPathResolver.activeVersion(t));
        assertEquals("0.16", ModelPathResolver.previousVersion(t));
        assertFalse("0.15 应被清理", ModelPathResolver.versionDir(t, "0.15").exists());
        assertTrue("0.16 应保留", ModelPathResolver.versionDir(t, "0.16").exists());
        assertTrue("0.17 应保留", ModelPathResolver.versionDir(t, "0.17").exists());
    }

    // ---- 旧平铺布局迁移 ----

    @Test
    public void rollbackActive_promotesPrevious_deletesBadActive() throws IOException {
        File t = target();
        createVersion(t, "v1");
        createVersion(t, "v2");
        ModelPathResolver.promote(t, "v1");
        ModelPathResolver.promote(t, "v2"); // active=v2, previous=v1
        assertTrue("应回滚", ModelPathResolver.rollbackActive(t));
        assertEquals("回滚后 active=v1", "v1", ModelPathResolver.activeVersion(t));
        assertNull("回滚后 previous 清空", ModelPathResolver.previousVersion(t));
        assertTrue("v1 应保留", ModelPathResolver.versionDir(t, "v1").exists());
        assertFalse("坏 active v2 应删除", ModelPathResolver.versionDir(t, "v2").exists());
    }

    @Test
    public void rollbackActive_noPrevious_returnsFalse_unchanged() throws IOException {
        File t = target();
        createVersion(t, "v1");
        ModelPathResolver.promote(t, "v1"); // active=v1, 无 previous
        assertFalse("无 previous 不回滚", ModelPathResolver.rollbackActive(t));
        assertEquals("指针不变", "v1", ModelPathResolver.activeVersion(t));
    }

    @Test
    public void rollbackActive_thenPromote_keepsRolledBackAsPrevious() throws IOException {
        // P1-5 完整风险链反例:active=v2(坏)→rollback→active=v1→装 v3→previous 应=v1(不被删)→v3 坏时仍有 v1
        File t = target();
        createVersion(t, "v1");
        createVersion(t, "v2");
        ModelPathResolver.promote(t, "v1");
        ModelPathResolver.promote(t, "v2");   // active=v2, previous=v1
        ModelPathResolver.rollbackActive(t);  // active=v1, previous=null, v2 删
        createVersion(t, "v3");
        ModelPathResolver.promote(t, "v3");   // active=v3, previous=v1(oldActive)
        assertEquals("active=v3", "v3", ModelPathResolver.activeVersion(t));
        assertEquals("previous=v1(回滚后保住,未被当 oldPrevious 删)", "v1", ModelPathResolver.previousVersion(t));
        assertTrue("v1 仍在", ModelPathResolver.versionDir(t, "v1").exists());
    }

    @Test
    public void migrateLegacy_movesFlatLayout_promotesActive() throws IOException {
        File t = target();
        // 旧平铺布局:targetDir 下直接有 marker(含子目录)+ 其它模型内容
        assertTrue(new File(t, "conf").mkdirs());
        assertTrue(new File(t, "conf/mfcc.conf").createNewFile());
        assertTrue(new File(t, "am").mkdirs());
        assertTrue(new File(t, "am/final.mdl").createNewFile());
        assertTrue(new File(t, "README").createNewFile());

        assertTrue("应执行迁移", ModelPathResolver.migrateLegacy(t, "0.15", "conf/mfcc.conf"));

        assertEquals("0.15", ModelPathResolver.activeVersion(t));
        File vd = ModelPathResolver.versionDir(t, "0.15");
        assertTrue("marker 应在版本目录", new File(vd, "conf/mfcc.conf").exists());
        assertTrue("其它内容应迁移", new File(vd, "am/final.mdl").exists());
        assertTrue(new File(vd, "README").exists());
        assertFalse("顶层 conf 应移走", new File(t, "conf").exists());
        assertFalse("顶层 am 应移走", new File(t, "am").exists());
    }

    @Test
    public void migrateLegacy_noOpWhenActiveExists() throws IOException {
        File t = target();
        createVersion(t, "0.15");
        ModelPathResolver.promote(t, "0.15");
        assertTrue(new File(t, "conf").mkdirs());
        assertTrue(new File(t, "conf/mfcc.conf").createNewFile());

        assertFalse("已有 active 应 no-op", ModelPathResolver.migrateLegacy(t, "0.16", "conf/mfcc.conf"));
        assertEquals("active 不应变", "0.15", ModelPathResolver.activeVersion(t));
        assertTrue("顶层内容不应被动", new File(t, "conf/mfcc.conf").exists());
    }

    @Test
    public void migrateLegacy_noOpWhenNoLegacyMarker() throws IOException {
        File t = target();
        assertFalse("无 marker 无 active 应 no-op",
                ModelPathResolver.migrateLegacy(t, "0.15", "conf/mfcc.conf"));
        assertNull(ModelPathResolver.activeVersion(t));
    }

    @Test
    public void migrateLegacy_recoversStagingResidue() throws IOException {
        // 事务性崩溃恢复:targetDir→staging 后崩溃、版本目录未建(staging 残留)。
        File t = target();
        File staging = new File(t.getParentFile(), t.getName() + ".migrating");
        assertTrue(new File(staging, "conf").mkdirs());
        assertTrue(new File(staging, "conf/mfcc.conf").createNewFile());

        assertTrue("应恢复 staging 残留并完成迁移", ModelPathResolver.migrateLegacy(t, "0.15", "conf/mfcc.conf"));
        assertEquals("0.15", ModelPathResolver.activeVersion(t));
        assertTrue(new File(ModelPathResolver.versionDir(t, "0.15"), "conf/mfcc.conf").exists());
        assertFalse("staging 应被消费", staging.exists());
    }

    @Test
    public void migrateLegacy_completesPartialPromote() throws IOException {
        // 事务性崩溃恢复:版本目录已就绪(marker 在)但 promote 未完成(无 active)。
        File t = target();
        File vd = ModelPathResolver.versionDir(t, "0.15");
        assertTrue(new File(vd, "conf").mkdirs());
        assertTrue(new File(vd, "conf/mfcc.conf").createNewFile());
        assertNull("迁移前无 active", ModelPathResolver.activeVersion(t));

        assertTrue("应补完 promote", ModelPathResolver.migrateLegacy(t, "0.15", "conf/mfcc.conf"));
        assertEquals("0.15", ModelPathResolver.activeVersion(t));
    }
}
