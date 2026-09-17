package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/**
 * 模型版本目录解析(纯 Java,JVM 可测)。
 *
 * <p>版本布局:
 * <ul>
 *   <li>{@code <targetDir>/versions/<version>/} —— 解压内容 + marker;</li>
 *   <li>{@code <targetDir>/active} —— 当前 active 版本号;</li>
 *   <li>{@code <targetDir>/previous} —— 上一版本号(回滚用)。</li>
 * </ul>
 *
 * <p>{@link #promote} 原子切换 active({@code active.tmp→rename} 同 FS 原子),
 * {@code previous=旧 active},清理更旧版本(保留 active+previous 两个)。中途崩溃 active 不更新
 * (仍指旧版本,安全)。加载侧(VoskModelHolder)读 active 失败时回退 previous。
 */
public final class ModelPathResolver {
    public static final String VERSIONS_DIR = "versions";
    static final String ACTIVE_FILE = "active";
    static final String PREVIOUS_FILE = "previous";

    private ModelPathResolver() { }

    /** 版本目录:<targetDir>/versions/<version>/。 */
    public static File versionDir(File targetDir, String version) {
        return new File(new File(targetDir, VERSIONS_DIR), version);
    }

    /** active 版本号;active 文件不存在/读失败→null。 */
    public static String activeVersion(File targetDir) {
        return readVersionFile(new File(targetDir, ACTIVE_FILE));
    }

    /** active 版本目录;无 active→null。 */
    public static File activeDir(File targetDir) {
        String v = activeVersion(targetDir);
        return v != null ? versionDir(targetDir, v) : null;
    }

    public static String previousVersion(File targetDir) {
        return readVersionFile(new File(targetDir, PREVIOUS_FILE));
    }

    public static File previousDir(File targetDir) {
        String v = previousVersion(targetDir);
        return v != null ? versionDir(targetDir, v) : null;
    }

    /**
     * 提升新版本为 active:原子写 active.tmp→rename;previous=旧 active(若有);清理更旧版本
     * (保留 active+previous)。
     */
    public static void promote(File targetDir, String newVersion) throws IOException {
        if (targetDir == null) throw new IllegalArgumentException("targetDir 不能为空");
        if (newVersion == null || newVersion.isEmpty()) throw new IllegalArgumentException("newVersion 不能为空");
        String oldActive = activeVersion(targetDir);
        String oldPrevious = previousVersion(targetDir);
        // 崩溃安全事务:先持久化 previous=oldActive,再切 active。崩溃在 active 切换前:
        // active 仍旧(加载旧版 OK),previous=oldActive → 可回退。两步都 tmp+fsync+rename(原子)。
        if (oldActive != null && !oldActive.equals(newVersion)) {
            atomicWriteVersion(targetDir, PREVIOUS_FILE, oldActive);
        }
        atomicWriteVersion(targetDir, ACTIVE_FILE, newVersion);
        // 清理更旧版本:删 oldPrevious(若它既非新 active 也非新 previous)
        if (oldPrevious != null
                && !oldPrevious.equals(newVersion)
                && !oldPrevious.equals(oldActive)) {
            deleteRecursive(versionDir(targetDir, oldPrevious));
        }
    }

    /**
     * active 加载失败后回滚:把 previous 提升为 active,删坏 active 版本目录,清 previous 指针。
     * 确保"最后一个已知可用版本"成为新 active;后续 promote 时坏版本不会被留作 previous,
     * 避免回滚链断裂(v2 坏→临时用 v1→装 v3 把 v2 留作 previous→v1 被删→v3 坏时无回滚)。
     * @return true=已回滚(active←previous);false=无 previous 可回滚(指针不变)。
     */
    public static boolean rollbackActive(File targetDir) throws IOException {
        if (targetDir == null) throw new IllegalArgumentException("targetDir 不能为空");
        String badActive = activeVersion(targetDir);
        String prev = previousVersion(targetDir);
        if (prev == null) return false;
        // P1-5a: 先原子写 active=prev + 清 previous,最后删坏 active 目录。
        // 崩溃在删目录前 → active 已指 prev(可加载),坏目录残留(下次清理),无"指针指已删目录"。
        atomicWriteVersion(targetDir, ACTIVE_FILE, prev);
        new File(targetDir, PREVIOUS_FILE).delete();
        if (badActive != null && !badActive.equals(prev)) {
            deleteRecursive(versionDir(targetDir, badActive));
        }
        return true;
    }

    /**
     * 旧平铺布局迁移到版本目录布局(升级保活已下载模型,不重下)。
     * <b>事务性 + 崩溃可恢复</b>。
     *
     * <p>用「整体 rename」而非逐个移动子文件——中途进程死亡不会留下「一半在根目录、一半在版本目录」
     * 且 marker 已移走导致误判不需迁移、只能重下的状态:
     * <pre>
     *   targetDir → staging(同级 {@code <name>.migrating}) → versions/&lt;version&gt;/ → promote 写 active
     * </pre>
     * 崩溃点仅两次 rename 之间,下次启动按三个检查点恢复:
     * <ol>
     *   <li>已有 active → no-op(顺手清 staging 残留);</li>
     *   <li>版本目录已就绪(marker 在)但无 active → 补 promote;</li>
     *   <li>staging 存在(targetDir→staging 后崩溃,版本目录未建)→ 完成 staging→版本目录 + promote。</li>
     * </ol>
     *
     * <p><b>fail-open</b>:迁移不校验 SHA-256——本地搬运威胁模型与"下载源→磁盘"链路不同,旧文件来源
     * 不可信且校验失败强制重下违背"保活旧投资"初衷(sha256 只管下载链路)。rename 同 FS 原子、O(1);
     * 第二次 rename 失败时回滚还原 targetDir。
     *
     * @return true 表示执行/恢复了迁移,false 表示 no-op(无需迁移)
     */
    public static boolean migrateLegacy(File targetDir, String version, String marker) throws IOException {
        if (targetDir == null) throw new IllegalArgumentException("targetDir 不能为空");
        if (version == null || version.isEmpty()) throw new IllegalArgumentException("version 不能为空");
        if (marker == null || marker.isEmpty()) throw new IllegalArgumentException("marker 不能为空");

        cleanTmpResidue(targetDir); // 清 promote 崩溃残留的 active.tmp/previous.tmp
        File staging = stagingDir(targetDir);
        File vd = versionDir(targetDir, version);

        // 1. 已是新布局 → no-op,顺手清 staging 残留(防御)。
        if (activeDir(targetDir) != null) {
            deleteRecursive(staging);
            return false;
        }
        // 2. 崩溃恢复:版本目录已就绪(marker 在)但 promote 未完成 → 补 promote。
        if (vd.exists() && new File(vd, marker).exists()) {
            deleteRecursive(staging);
            promote(targetDir, version);
            return true;
        }
        // 3. 崩溃恢复:staging 存在(targetDir→staging 后崩溃,版本目录未建)→ 完成迁移。
        if (staging.exists()) {
            File parent = vd.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) { // 父目录已存在则跳过 mkdirs(否则返回 false 误抛,死循环)
                throw new IOException("无法创建版本目录父级: " + parent);
            }
            if (!staging.renameTo(vd)) {
                throw new IOException("恢复迁移失败: rename " + staging + " -> " + vd);
            }
            promote(targetDir, version);
            return true;
        }
        // 4. 正常路径:无旧平铺布局(marker 不在 targetDir 顶层) → no-op。
        if (!new File(targetDir, marker).exists()) {
            return false;
        }
        // 5. 事务性迁移:整体 rename targetDir→staging→versionDir→promote。
        if (!targetDir.renameTo(staging)) {
            throw new IOException("迁移 rename 失败: " + targetDir + " -> " + staging);
        }
        if (!vd.getParentFile().exists() && !vd.getParentFile().mkdirs()) {
            staging.renameTo(targetDir); // 回滚还原 targetDir
            throw new IOException("无法创建版本目录父级: " + vd.getParentFile());
        }
        if (!vd.exists() && !staging.renameTo(vd)) {
            staging.renameTo(targetDir); // 回滚还原 targetDir
            throw new IOException("迁移 rename 失败: " + staging + " -> " + vd);
        }
        promote(targetDir, version);
        return true;
    }

    /** 同级 staging 目录:{@code <targetDir>.migrating}(整体改名的落点)。 */
    static File stagingDir(File targetDir) {
        return new File(targetDir.getParentFile(), targetDir.getName() + ".migrating");
    }

    private static String readVersionFile(File f) {
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return (line != null) ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 原子写版本文件:tmp 写入 + fsync(抗普通进程死亡) + rename(同 FS 原子)。崩溃在 rename 前目标不变。
     * <b>未 fsync rename 所在目录,不保证掉电 durable</b>(只抗进程死亡)。 */
    private static void atomicWriteVersion(File targetDir, String fileName, String version) throws IOException {
        File tmp = new File(targetDir, fileName + ".tmp");
        File target = new File(targetDir, fileName);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建: " + parent);
        }
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
            out.getFD().sync(); // 文件内容落盘(抗进程死亡;目录未 fsync,不保证掉电 durable)
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("原子写失败: " + tmp + " -> " + target);
        }
    }

    /** 清理崩溃残留的 active.tmp/previous.tmp(promote 在 rename 前崩溃遗留,下次启动清)。 */
    private static void cleanTmpResidue(File targetDir) {
        new File(targetDir, ACTIVE_FILE + ".tmp").delete();
        new File(targetDir, PREVIOUS_FILE + ".tmp").delete();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }
}
