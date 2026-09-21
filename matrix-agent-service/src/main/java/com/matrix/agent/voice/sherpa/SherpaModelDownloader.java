package com.matrix.agent.voice.sherpa;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;
import com.matrix.agent.voice.Sha256Util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Sherpa 模型下载器——复用 ModelDownloadEntity/DAO 记录进度，
 * 下载→SHA-256→解压（tar.bz2/zip/RAW）→版本目录→原子 promote，
 * 与 VoskModelDownloader 同管道、同状态语义。
 *
 * <p>与 Vosk 的差异：上游发布为 tar.bz2（commons-compress 解压）或单文件（RAW 直落）；
 * 大包含多精度变体，解压按 {@link SherpaModelSpec#requiredFiles} 白名单只落盘
 * 需要的一套，落盘后逐项校验存在（缺一即 FAILED）。</p>
 */
public final class SherpaModelDownloader {

    private static final String TAG = "MatrixAgent";
    private static final int PROGRESS_THRESHOLD = 512 * 1024;

    private final ModelDownloadDao dao;
    private final Context appContext;
    private final OkHttpClient httpClient;

    public SherpaModelDownloader(Context context, ModelDownloadDao dao,
            OkHttpClient httpClient) {
        this.appContext = context.getApplicationContext();
        this.dao = dao;
        this.httpClient = httpClient;
    }

    /** active 版本目录存在且 marker 就绪。 */
    public boolean isDownloaded(SherpaModelSpec spec) {
        File active = com.matrix.agent.voice.ModelPathResolver.activeDir(spec.targetDir);
        if (active == null) return false;
        return new File(active, spec.marker).exists();
    }

    /** Host 投影：安装状态 + 进度。 */
    public com.matrix.agent.api.download.ModelDownloadInfo modelInfo(SherpaModelSpec spec) {
        ModelDownloadEntity entity = null;
        if (dao != null) {
            try {
                entity = dao.getByName(spec.name);
            } catch (Exception e) {
                Log.w(TAG, "[SherpaDL] 读取进度失败: " + e.getClass().getSimpleName());
            }
        }
        if (isDownloaded(spec)) {
            File active = com.matrix.agent.voice.ModelPathResolver.activeDir(spec.targetDir);
            long installed = active == null ? 0 : dirSize(active);
            return new com.matrix.agent.api.download.ModelDownloadInfo(spec.name,
                    com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED,
                    installed, installed, com.matrix.agent.api.common.MatrixErrorCode.SUCCESS,
                    spec.version);
        }
        long downloaded = entity == null ? 0 : Math.max(0, entity.downloadedBytes);
        long total = entity == null || entity.totalBytes <= 0 ? spec.sizeBytes : entity.totalBytes;
        int state = downloadState(entity == null ? null : entity.status);
        // A model that is not runnable must never be projected as a successful operation.  This
        // mirrors the Vosk downloader contract and lets UI distinguish a retryable download
        // failure from an idle, not-yet-requested model.
        int error = state == com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_FAILED
                ? com.matrix.agent.api.common.MatrixErrorCode.TASK_FAILED
                : com.matrix.agent.api.common.MatrixErrorCode.SUCCESS;
        return new com.matrix.agent.api.download.ModelDownloadInfo(spec.name,
                state, downloaded, total, error, spec.version);
    }

    /** 删除模型（递归 + DAO 清理）。 */
    public void delete(SherpaModelSpec spec) throws IOException {
        deleteRecursive(spec.targetDir);
        if (dao != null) {
            try { dao.deleteByName(spec.name); } catch (Exception ignored) { }
        }
    }

    /** 下载并安装。 */
    public void download(SherpaModelSpec spec, BooleanSupplier cancelled) throws IOException {
        File parent = spec.targetDir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("CREATE_DIR_FAILED: " + spec.name);
        }
        String suffix = spec.format == SherpaModelSpec.Format.RAW
                ? requiredFile(spec) : spec.format.name().toLowerCase();
        File tmpArchive = new File(parent, ".tmp_sherpa_" + spec.name + "." + suffix);

        try {
            setStatus(spec, "DOWNLOADING", 0, 0);
            downloadArchive(spec, tmpArchive, cancelled);
            if (cancelled.getAsBoolean()) throw new IOException("CANCELLED");

            setStatus(spec, "DOWNLOADING", tmpArchive.length(), 0);
            verifySha256(tmpArchive, spec.sha256, spec.name);
            if (cancelled.getAsBoolean()) throw new IOException("CANCELLED");

            File versionDir = new File(new File(spec.targetDir, "versions"), spec.version);
            if (versionDir.exists()) deleteRecursive(versionDir);
            if (!versionDir.mkdirs()) {
                throw new IOException("MKDIR_FAILED: " + spec.name);
            }
            switch (spec.format) {
                case RAW:
                    installRaw(tmpArchive, versionDir, spec);
                    break;
                case TAR_BZ2:
                    untarBz2Flatten(tmpArchive, versionDir, spec, cancelled);
                    break;
                case ZIP:
                    unzipFlatten(tmpArchive, versionDir, spec, cancelled);
                    break;
                default:
                    throw new IOException("UNSUPPORTED_FORMAT: " + spec.format);
            }

            // 白名单逐项校验：缺任一即安装失败（防上游包结构变更静默半装）
            for (String required : spec.requiredFiles) {
                if (!new File(versionDir, required).isFile()) {
                    throw new IOException("MISSING_REQUIRED: " + spec.name + "/" + required);
                }
            }
            File markerFile = new File(versionDir, spec.marker);
            if (!markerFile.exists()) {
                throw new IOException("MARKER_MISSING: " + spec.name + "/" + spec.marker);
            }

            com.matrix.agent.voice.ModelPathResolver.promote(spec.targetDir, spec.version);
            tmpArchive.delete();

            long size = dirSize(versionDir);
            setStatus(spec, "COMPLETED", size, size);
            Log.i(TAG, "[SherpaDL] 模型就绪: " + spec.name + " v" + spec.version
                    + " size=" + size);
        } catch (IOException e) {
            boolean cancelledNow = cancelled.getAsBoolean()
                    || "CANCELLED".equals(e.getMessage());
            long resumeBytes = tmpArchive.exists() ? tmpArchive.length() : 0L;
            if (cancelledNow) {
                // PAUSED has one meaning only: lifecycle/user cancellation. Keep the archive so
                // a future install can issue an HTTP Range request from this exact boundary.
                setStatus(spec, "PAUSED", resumeBytes, 0);
            } else if (isTransientNetworkFailure(e)) {
                // A timeout/DNS/socket reset is not a corrupt model and must not be mislabeled as
                // “paused”. Preserve bytes and expose FAILED; the next explicit install resumes.
                setStatus(spec, "FAILED", resumeBytes, 0);
                Log.w(TAG, "[SherpaDL] 可恢复网络失败 " + spec.name
                        + " code=" + networkFailureCode(e)
                        + " resumeBytes=" + resumeBytes);
            } else {
                Log.e(TAG, "[SherpaDL] 下载失败 " + spec.name + " code=" + e.getMessage());
                setStatus(spec, "FAILED", 0, 0);
                deleteRecursive(tmpArchive);
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- 内部

    private static String requiredFile(SherpaModelSpec spec) {
        return spec.requiredFiles.length > 0 ? spec.requiredFiles[0] : spec.marker;
    }

    private void downloadArchive(SherpaModelSpec spec, File tmpFile,
            BooleanSupplier cancelled) throws IOException {
        // A previous process can have received the final byte and died before it got to SHA
        // verification/extraction.  Do not issue an invalid Range request for that complete
        // archive (many CDNs answer 416) and do not force a second network transfer.  The pinned
        // digest remains the admission check, so an equal-size corrupt file is discarded below.
        if (isCompleteVerifiedArchive(spec, tmpFile)) return;
        try {
            downloadArchiveFrom(spec, spec.url, tmpFile, cancelled);
        } catch (IOException primaryFailure) {
            if (spec.fallbackUrl == null || spec.fallbackUrl.isEmpty() || cancelled.getAsBoolean()) {
                throw primaryFailure;
            }
            // Preserve an already-received prefix. Both sources are required to produce the
            // pinned digest, and Range makes this a safe continuation rather than a restart.
            Log.w(TAG, "[SherpaDL] 主源不可用，切换受校验备用源 model=" + spec.name
                    + " type=" + primaryFailure.getClass().getSimpleName());
            downloadArchiveFrom(spec, spec.fallbackUrl, tmpFile, cancelled);
        }
    }

    static boolean isCompleteVerifiedArchive(SherpaModelSpec spec, File archive)
            throws IOException {
        if (!archive.isFile() || spec.sizeBytes <= 0 || archive.length() != spec.sizeBytes) {
            return false;
        }
        try {
            verifySha256(archive, spec.sha256, spec.name);
            return true;
        } catch (IOException invalid) {
            if (!archive.delete()) {
                throw new IOException("TMP_DELETE_FAILED: " + spec.name, invalid);
            }
            return false;
        }
    }

    private void downloadArchiveFrom(SherpaModelSpec spec, String url, File tmpFile,
            BooleanSupplier cancelled) throws IOException {
        Request.Builder request = new Request.Builder().url(url).get();
        long existing = tmpFile.exists() ? tmpFile.length() : 0;
        if (existing > 0) request.header("Range", "bytes=" + existing + "-");
        okhttp3.Call call = httpClient.newCall(request.build());
        try (Response response = call.execute()) {
            int code = response.code();
            if (code != 200 && code != 206) {
                throw new IOException("HTTP_" + code + ": " + spec.name);
            }
            boolean append = code == 206 && existing > 0;
            if (!append && tmpFile.exists() && !tmpFile.delete()) {
                throw new IOException("TMP_DELETE_FAILED: " + spec.name);
            }
            if (response.body() == null) throw new IOException("EMPTY_RESPONSE: " + spec.name);
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(tmpFile, append)) {
                byte[] buf = new byte[8192];
                int n;
                long downloaded = append ? existing : 0;
                long lastReported = downloaded;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.getAsBoolean()) throw new IOException("CANCELLED");
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (downloaded - lastReported >= PROGRESS_THRESHOLD) {
                        setStatus(spec, "DOWNLOADING", downloaded, 0);
                        lastReported = downloaded;
                    }
                }
            }
        } finally {
            call.cancel();
        }
    }

    private static void verifySha256(File file, String expected, String name) throws IOException {
        if (expected == null || expected.isEmpty()) return;
        String actual = Sha256Util.sha256Hex(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("HASH_MISMATCH: " + name);
        }
    }

    private static boolean isTransientNetworkFailure(Throwable error) {
        for (Throwable current = error; current != null && current.getCause() != current;
                current = current.getCause()) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.NoRouteToHostException
                    || current instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static String networkFailureCode(Throwable error) {
        for (Throwable current = error; current != null && current.getCause() != current;
                current = current.getCause()) {
            if (current instanceof java.net.SocketTimeoutException) return "NETWORK_TIMEOUT";
            if (current instanceof java.net.UnknownHostException) return "NETWORK_DNS";
            if (current instanceof java.net.ConnectException) return "NETWORK_CONNECT";
            if (current instanceof java.net.NoRouteToHostException) return "NETWORK_UNREACHABLE";
            if (current instanceof java.io.InterruptedIOException) return "NETWORK_INTERRUPTED";
        }
        return "NETWORK_IO";
    }

    /** RAW 单文件：下载产物即模型本体，改名为 requiredFiles[0] 落位。包级可见：JVM 单测直测。 */
    static void installRaw(File downloaded, File versionDir, SherpaModelSpec spec)
            throws IOException {
        File out = secureResolve(versionDir, requiredFile(spec), spec);
        try (FileInputStream in = new FileInputStream(downloaded);
             FileOutputStream fo = new FileOutputStream(out)) {
            copy(in, fo);
        }
    }

    private static void unzipFlatten(File archive, File destDir, SherpaModelSpec spec,
            BooleanSupplier cancelled) throws IOException {
        Set<String> keep = keepSet(spec);
        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (cancelled.getAsBoolean()) throw new IOException("CANCELLED");
                String relative = flattenPath(entry.getName());
                if (relative.isEmpty() || !keep.contains(relative)) {
                    zis.closeEntry(); // 白名单外（多精度变体/测试音频）直接跳过
                    continue;
                }
                File out = secureResolve(destDir, relative, spec);
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File p = out.getParentFile();
                    if (p != null && !p.exists() && !p.mkdirs()) {
                        throw new IOException("MKDIR_FAILED: " + spec.name);
                    }
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        copy(zis, fo);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** tar.bz2 白名单解压。包级可见：JVM 单测直测。 */
    static void untarBz2Flatten(File archive, File destDir, SherpaModelSpec spec,
            BooleanSupplier cancelled) throws IOException {
        Set<String> keep = keepSet(spec);
        try (TarArchiveInputStream tis = new TarArchiveInputStream(
                new BZip2CompressorInputStream(
                        new BufferedInputStream(new FileInputStream(archive))))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                if (cancelled.getAsBoolean()) throw new IOException("CANCELLED");
                String relative = flattenPath(entry.getName());
                if (relative.isEmpty() || !keep.contains(relative)) continue;
                File out = secureResolve(destDir, relative, spec);
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File p = out.getParentFile();
                    if (p != null && !p.exists() && !p.mkdirs()) {
                        throw new IOException("MKDIR_FAILED: " + spec.name);
                    }
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        copy(tis, fo);
                    }
                }
            }
        }
    }

    /** 白名单集合（requiredFiles 展开）。 */
    private static Set<String> keepSet(SherpaModelSpec spec) {
        Set<String> keep = new HashSet<>();
        for (String f : spec.requiredFiles) keep.add(f);
        return keep;
    }

    /** 解析 destDir 内相对路径并做 zip-slip/tar-slip 防护。包级可见：JVM 单测直测。 */
    static File secureResolve(File destDir, String relative, SherpaModelSpec spec)
            throws IOException {
        File out = new File(destDir, relative);
        String canonical = out.getCanonicalPath();
        if (!canonical.startsWith(destDir.getCanonicalPath() + File.separator)) {
            throw new IOException("ARCHIVE_SLIP: " + spec.name);
        }
        return out;
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** 去掉顶层包裹目录（sherpa 上游 tar/zip 均有一层模型名目录）。 */
    private static String flattenPath(String name) {
        int slash = name.indexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private void setStatus(SherpaModelSpec spec, String status, long downloaded, long total) {
        if (dao == null) return;
        try {
            ModelDownloadEntity e = dao.getByName(spec.name);
            boolean existed = e != null;
            if (e == null) {
                e = new ModelDownloadEntity();
                e.modelName = spec.name;
                e.displayName = spec.modelId;
                e.sourceRepo = spec.url;
                e.createdAt = System.currentTimeMillis();
            }
            e.downloadedBytes = downloaded;
            e.totalBytes = total;
            e.status = status;
            e.updatedAt = System.currentTimeMillis();
            if (existed) dao.update(e); else dao.upsert(e);
        } catch (Exception ex) {
            Log.w(TAG, "[SherpaDL] 进度写入失败: " + ex.getMessage());
        }
    }

    private static long dirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) size += f.isDirectory() ? dirSize(f) : f.length();
        }
        return size;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private static int downloadState(String value) {
        if ("DOWNLOADING".equals(value)) return com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING;
        if ("PAUSED".equals(value)) return com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_PAUSED;
        if ("COMPLETED".equals(value)) return com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED;
        if ("FAILED".equals(value)) return com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_FAILED;
        return com.matrix.agent.api.download.ModelDownloadInfo.DOWNLOAD_STATE_IDLE;
    }
}
