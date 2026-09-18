package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import android.content.Context;
import android.os.storage.StorageManager;
import android.util.Log;

import com.matrix.agent.voice.ModelInstallLock;
import com.matrix.agent.voice.ModelPathResolver;
import com.matrix.agent.voice.Sha256Util;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Vosk 模型下载器。
 *
 * <p>{@code ModelDownloadManager} 与 MNN/ModelScope 强绑定无法复用;本类独立实现 Vosk zip 下载 + 解压,
 * 复用 {@link ModelDownloadEntity}/{@link ModelDownloadDao} 记录进度。
 *
 * <p><b>模型治理</b>:下载后 SHA-256 校验(spec.sha256 非空时,防篡改/损坏)→ 解压到版本目录
 * {@code <targetDir>/versions/<version>/}→ {@link ModelPathResolver#promote} 原子切换 active
 * (previous=旧 active,保留回滚)。{@link #isDownloaded} 检查 active 指针 + marker。
 *
 * <p><b>锁与并发</b>:统一经 {@link ModelInstallLock}(进程内 per-langDir + 跨进程 FileLock)。
 * 锁竞争(另一实例正在装同 langDir)抛 {@link ModelInstallLock.LockBusyException} <b>不删任何 tmp</b>
 * (持锁者正在用);取消/瞬态网络错误保留断点(PAUSED);真实损坏(校验/解压)删 tmp(FAILED)。
 * migrateLegacy 也在锁内(防与 promote/rollback 竞争指针)。
 *
 * <p><b>取消语义</b>:取消抛 {@link DownloadCancelledException}——保留 {@code .tmp_<name>_<version>.zip}
 * 断点(PAUSED,下次 Range 续传),只删解压中途目录。解压内层读循环也检查取消(每 1MB),大单文件可及时停。
 *
 * <p><b>日志合规</b>:所有异常消息以稳定 code 开头(冒号前),Log.e 只记模型名+版本+code+异常类,
 * <b>不记录绝对模型路径</b>(符合文档门禁)。
 *
 * <p><b>dao 可空</b>:数据库降级时进度降级为仅日志,不崩。
 */
public final class VoskModelDownloader {
    private static final String TAG = "MatrixAgent";
    private static final int PROGRESS_THRESHOLD = 512 * 1024;
    /** 解压内层读循环取消检查间隔(大单文件 final.mdl/graph.bin 可及时停)。 */
    private static final long UNZIP_CANCEL_BYTES = 1024 * 1024;
    /**
     * Unknown-size specs are supported for compatibility, but must still have a hard streaming
     * ceiling.  Otherwise a server which omits Content-Length can fill app storage before the
     * later ZIP/SHA checks get a chance to reject it.
     */
    private static final long MAX_UNKNOWN_ZIP_BYTES = 1024L * 1024L * 1024L;
    /** 唯一事务目录序号(跨实例),防并发安装撞固定 .tmp 目录名。 */
    private static final java.util.concurrent.atomic.AtomicLong uidSeq = new java.util.concurrent.atomic.AtomicLong();

    private final ModelDownloadDao dao; // 可空
    private final Context appContext; // 可空仅为保留纯 JVM downloader 测试构造器
    private final OkHttpClient httpClient;

    public VoskModelDownloader(ModelDownloadDao dao) {
        this(null, dao, new com.matrix.agent.platform.MatrixHttpClient().download());
    }

    /** Production constructor: supplies Android's allocatable-space service to preflight. */
    public VoskModelDownloader(Context context, ModelDownloadDao dao) {
        this(context, dao, new com.matrix.agent.platform.MatrixHttpClient().download());
    }

    public VoskModelDownloader(Context context, ModelDownloadDao dao, OkHttpClient httpClient) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.dao = dao;
        this.httpClient = httpClient;
    }

    /** active 版本目录存在且 marker 就绪。 */
    public boolean isDownloaded(VoskModelSpec spec) {
        File active = ModelPathResolver.activeDir(spec.targetDir);
        if (active == null) return false;
        // active 版本必须等于 spec.version:否则旧版本(如 0.15)的 marker 会让升级(0.16)误判已下载。
        if (!spec.version.equals(ModelPathResolver.activeVersion(spec.targetDir))) return false;
        return new File(active, spec.marker).exists();
    }

    /**
     * 旧平铺布局迁移到版本目录布局(升级保活已下载模型,幂等)。委托
     * {@link ModelPathResolver#migrateLegacy};应在 {@link #isDownloaded} 之前调用,无旧布局时 no-op。
     */
    public boolean migrateLegacy(VoskModelSpec spec) throws IOException {
        // P3-1: public 入口也走统一模型锁(防调用方绕锁改指针);download 锁内直调 ModelPathResolver 避免重入
        boolean[] result = {false};
        ModelInstallLock.withTryLock(spec.targetDir, () ->
                result[0] = ModelPathResolver.migrateLegacy(spec.targetDir, spec.version, spec.marker));
        return result[0];
    }

    /** 下载并解压一个模型。cancelled 由调用方持有(cleared/generation)。 */
    public void download(VoskModelSpec spec, BooleanSupplier cancelled) throws IOException {
        File parent = spec.targetDir.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("CREATE_DIR_FAILED: " + spec.name);
        }
        File versionsDir = new File(spec.targetDir, ModelPathResolver.VERSIONS_DIR);
        if (!versionsDir.exists() && !versionsDir.mkdirs()) {
            throw new IOException("CREATE_VERSIONS_DIR_FAILED: " + spec.name);
        }
        // tmpZip 含 version:跨版本不共享断点(避免旧版本前缀与新 Range 响应拼接致 hash 失败)。
        File tmpZip = new File(parent, ".tmp_" + spec.name + "_" + spec.version + ".zip");
        File tmpVersionDir = new File(versionsDir, ".tmp_" + spec.version + "_" + uidSeq.incrementAndGet());
        File finalVersionDir = ModelPathResolver.versionDir(spec.targetDir, spec.version);
        ensureStorageAllocatable(spec); // 存储预检(zip+解压峰值+余量)
        try {
            ModelInstallLock.withTryLock(spec.targetDir, () -> {
                // P1-3: 先复查已完成(不写 DOWNLOADING 覆盖 COMPLETED)
                if (isDownloaded(spec)) return;
                // P1-5b/P3-1: 锁内直调 ModelPathResolver.migrateLegacy(避免 this.migrateLegacy 重入锁)
                ModelPathResolver.migrateLegacy(spec.targetDir, spec.version, spec.marker);
                if (isDownloaded(spec)) return; // 迁移后可能已就绪
                setStatus(spec, "DOWNLOADING", tmpZip.exists() ? tmpZip.length() : 0, 0);
                checkCancelled(cancelled);
                downloadZip(spec, tmpZip, cancelled);
                checkCancelled(cancelled);
                verifySha256(tmpZip, spec.sha256, spec.name);
                checkCancelled(cancelled);
                unzipFlatten(tmpZip, tmpVersionDir, spec, cancelled);
                checkCancelled(cancelled);
                verifyMarker(tmpVersionDir, spec.marker, spec.name);
                if (finalVersionDir.exists()) deleteRecursive(finalVersionDir);
                if (!tmpVersionDir.renameTo(finalVersionDir)) {
                    throw new IOException("RENAME_FAILED: " + spec.name + " v" + spec.version);
                }
                ModelPathResolver.promote(spec.targetDir, spec.version);
                tmpZip.delete();
                long size = dirSize(finalVersionDir);
                setStatus(spec, "COMPLETED", size, size);
                Log.i(TAG, "[Voice] Vosk 模型就绪: " + spec.name + " v" + spec.version);
            });
        } catch (ModelInstallLock.LockBusyException e) {
            // 锁竞争(跨进程/实例占用)——不删任何 tmp(持锁者正在用),向上传播让调用方轮询/跳过
            Log.i(TAG, "[Voice] 模型正在被另一实例安装,跳过: " + spec.name);
            throw e;
        } catch (Exception e) {
            // 取消 / 瞬态网络(PAUSED 保断点)/ 真实损坏(FAILED 删 zip)(P2-C 分类)
            boolean cancelledNow = (e instanceof DownloadCancelledException)
                    || cancelled.getAsBoolean()
                    || Thread.currentThread().isInterrupted();
            boolean transientNetwork = !cancelledNow && isTransientNetwork(e);
            long have = tmpZip.exists() ? tmpZip.length() : 0;
            if (cancelledNow || transientNetwork) {
                setStatus(spec, "PAUSED", have, 0); // 保留断点,下次 Range 续传
                deleteRecursive(tmpVersionDir);
                Log.i(TAG, "[Voice] 下载暂停(保留断点," + have + " B): " + spec.name
                        + (transientNetwork ? "(网络)" : "(取消)"));
            } else {
                // 日志只记模型名+版本+code+异常类,不含绝对路径
                Log.e(TAG, "[Voice] Vosk 模型下载失败 " + spec.name + " v" + spec.version
                        + " code=" + errorCode(e) + " type=" + e.getClass().getSimpleName());
                setStatus(spec, "FAILED", 0, 0);
                tmpZip.delete();
                deleteRecursive(tmpVersionDir);
            }
            throw e instanceof IOException ? (IOException) e : new IOException(errorCode(e), e);
        }
    }

    /** 安装前存储预检:峰值 = 完整 zip(sizeBytes)+ 解压(installSizeBytes)并存 + 10% 余量;未配置跳过。 */
    private void ensureStorageAllocatable(VoskModelSpec spec) throws IOException {
        long zip = spec.sizeBytes > 0 ? spec.sizeBytes : 0;
        long install = spec.installSizeBytes > 0 ? spec.installSizeBytes : 0;
        long need = zip > Long.MAX_VALUE - install ? Long.MAX_VALUE : zip + install;
        if (need <= 0) return;
        long required = need > Long.MAX_VALUE / 11L ? Long.MAX_VALUE : need * 11L / 10L;
        if (spec.targetDir.getUsableSpace() >= required) return;
        if (appContext != null) {
            StorageManager storage = appContext.getSystemService(StorageManager.class);
            if (storage != null) {
                try {
                    UUID uuid = storage.getUuidForPath(spec.targetDir);
                    if (storage.getAllocatableBytes(uuid) >= required) {
                        storage.allocateBytes(uuid, required);
                        if (spec.targetDir.getUsableSpace() >= required) return;
                    }
                } catch (IOException ignored) {
                    // One final usable-space check below produces the stable failure code.
                }
            }
        }
        throw new IOException("INSUFFICIENT_STORAGE: " + spec.name);
    }

    /** P2-2: 瞬态网络错误(递归 cause)——连接/超时/DNS/Socket(Connection reset/Broken pipe)/EOF/HTTP 429/408/5xx → PAUSED 保断点;真实损坏(HASH/ZIP_SLIP/404)→ FAILED 删。 */
    private static boolean isTransientNetwork(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketException // Connection reset / Broken pipe
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.io.InterruptedIOException
                    || t instanceof java.io.EOFException) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.startsWith("HTTP_429") || msg.startsWith("HTTP_408") || msg.startsWith("HTTP_5"))) {
                return true;
            }
        }
        return false;
    }

    /** SHA-256 校验:expected 非空时比对(不匹配抛),空时跳过(warning,防篡改未启用)。 */
    private static void verifySha256(File zip, String expected, String name) throws IOException {
        if (expected == null || expected.isEmpty()) {
            Log.w(TAG, "[Voice] 模型未配置 SHA-256,跳过完整性校验(防篡改未启用): " + name);
            return;
        }
        String actual = Sha256Util.sha256Hex(zip);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("HASH_MISMATCH: " + name);
        }
        Log.i(TAG, "[Voice] 模型 SHA-256 校验通过: " + name);
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws DownloadCancelledException {
        if (cancelled.getAsBoolean()) throw new DownloadCancelledException();
    }

    private void downloadZip(VoskModelSpec spec, File tmpZip, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        long maxZipBytes = maxZipBytes(spec);
        Request.Builder request = new Request.Builder().url(spec.zipUrl).get();
        long existing = tmpZip.exists() ? tmpZip.length() : 0;
            if (existing > maxZipBytes) {
                throw new IOException("ZIP_TOO_LARGE: " + spec.name);
            }
            if (existing > 0) request.header("Range", "bytes=" + existing + "-");
            Call call = httpClient.newCall(request.build());
            try (Response response = call.execute()) {
            int code = response.code();
            // P1-2: 仅 200/206 进入写文件;其他(429/408/5xx/4xx)抛 HTTP_<code>(不删 tmpZip,
            // 由 catch 按 isTransientNetwork 决定 PAUSED 保断点 vs FAILED 删)。
            if (code != 200 && code != 206) {
                throw new IOException("HTTP_" + code + ": " + spec.name);
            }
            boolean append = code == 206 && existing > 0;
            if (!append && tmpZip.exists() && !tmpZip.delete()) {
                throw new IOException("TMP_DELETE_FAILED: " + spec.name);
            }
            long contentLen = response.body() == null ? 0 : response.body().contentLength();
            long downloaded = append ? existing : 0;
            // Check the advertised length before opening the output file, but do not trust it:
            // the streaming loop below performs the same bound for chunked/misreported bodies.
            if (contentLen > 0 && contentLen > maxZipBytes - downloaded) {
                throw new IOException("ZIP_TOO_LARGE: " + spec.name);
            }
            long fullTotal = append && contentLen > 0 ? safeAdd(existing, contentLen) : contentLen;
            setStatus(spec, "DOWNLOADING", downloaded, fullTotal);
            if (response.body() == null) {
                throw new IOException("EMPTY_RESPONSE: " + spec.name);
            }
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(tmpZip, append)) {
                byte[] buf = new byte[8192];
                int n;
                long lastReported = downloaded;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.getAsBoolean()) throw new DownloadCancelledException();
                    if (downloaded > maxZipBytes - n) {
                        throw new IOException("ZIP_TOO_LARGE: " + spec.name);
                    }
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (downloaded - lastReported >= PROGRESS_THRESHOLD) {
                        setStatus(spec, "DOWNLOADING", downloaded, fullTotal);
                        lastReported = downloaded;
                    }
                }
            }
            } finally {
                call.cancel();
            }
    }

    private static long maxZipBytes(VoskModelSpec spec) {
        return spec.sizeBytes > 0 ? spec.sizeBytes : MAX_UNKNOWN_ZIP_BYTES;
    }

    private static long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /** 解压并 flatten(去顶层目录)。内层读循环检查取消(每 1MB)+ 解压大小上限(zip bomb 防护)。 */
    private void unzipFlatten(File zip, File destDir, VoskModelSpec spec, BooleanSupplier cancelled) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("UNZIP_DIR_FAILED: " + spec.name);
        }
        String base = destDir.getCanonicalPath();
        long cap = spec.installSizeBytes > 0
                ? (spec.installSizeBytes > Long.MAX_VALUE / 2L
                        ? Long.MAX_VALUE : spec.installSizeBytes * 2L)
                : 0;
        long totalWritten = 0;
        long lastCancelCheck = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkCancelled(cancelled); // 逐 entry 取消
                String name = entry.getName();
                int slash = name.indexOf('/');
                String relative = slash >= 0 ? name.substring(slash + 1) : name;
                if (relative.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(destDir, relative);
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(base + File.separator)) {
                    throw new IOException("ZIP_SLIP: " + spec.name);
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File p = out.getParentFile();
                    if (p != null && !p.exists() && !p.mkdirs()) {
                        throw new IOException("MKDIR_FAILED: " + spec.name);
                    }
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fo.write(buf, 0, n);
                            totalWritten += n;
                            // 内层读循环取消(每 1MB)+ 中断:大单文件(final.mdl/graph.bin)可及时停
                            if (totalWritten - lastCancelCheck >= UNZIP_CANCEL_BYTES) {
                                checkCancelled(cancelled);
                                if (Thread.currentThread().isInterrupted()) throw new DownloadCancelledException();
                                lastCancelCheck = totalWritten;
                            }
                            if (cap > 0 && totalWritten > cap) {
                                throw new IOException("UNZIP_TOO_LARGE: " + spec.name);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void verifyMarker(File dir, String marker, String name) throws IOException {
        if (!new File(dir, marker).exists()) {
            throw new IOException("MARKER_MISSING: " + name + "/" + marker);
        }
    }

    /** 从异常消息提取稳定错误码(冒号前;无冒号用类名),用于日志/上抛,不含路径。 */
    private static String errorCode(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return e.getClass().getSimpleName();
        int colon = msg.indexOf(':');
        return colon > 0 ? msg.substring(0, colon) : msg;
    }

    private void setStatus(VoskModelSpec spec, String status, long downloaded, long total) {
        String mb = (total > 0 ? (downloaded >> 20) + "/" + (total >> 20) + " MiB"
                : (downloaded >> 20) + " MiB");
        Log.i(TAG, "[Voice] " + spec.name + " " + status + " " + mb);
        if (dao == null) return;
        long now = System.currentTimeMillis();
        // createdAt 保留旧记录(首次 DOWNLOADING 时刻),避免每次进度更新/COMPLETED 覆盖为 now。
        long createdAt = now;
        try {
            ModelDownloadEntity old = dao.getByName(spec.name);
            if (old != null && old.createdAt > 0) createdAt = old.createdAt;
        } catch (Exception ex) {
            Log.w(TAG, "[Voice] 旧进度读取失败(createdAt 用当前时间): " + ex.getMessage());
        }
        ModelDownloadEntity e = new ModelDownloadEntity();
        e.modelName = spec.name;
        e.displayName = spec.name;
        e.sourceRepo = spec.zipUrl;
        e.downloadedBytes = downloaded;
        e.totalBytes = total;
        e.status = status;
        e.createdAt = createdAt;
        e.updatedAt = now;
        try {
            dao.upsert(e);
        } catch (Exception ex) {
            Log.w(TAG, "[Voice] 进度写入失败: " + ex.getMessage());
        }
    }

    /** 递归计算目录总大小(File.length() 对目录无意义,恒 0)。 */
    private static long dirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? dirSize(f) : f.length();
            }
        }
        return size;
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

    /** 取消信号触发的异常;catch 后保留断点、标 PAUSED。 */
    static final class DownloadCancelledException extends IOException {
        DownloadCancelledException() {
            super("CANCELLED");
        }
    }
}
