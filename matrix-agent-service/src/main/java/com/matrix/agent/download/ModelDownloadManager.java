package com.matrix.agent.download;

import android.content.Context;
import android.os.storage.StorageManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 端侧 MNN 模型下载管理器——串行下载、断点续传、原子落盘。
 *
 * <p>模型最终落到 {@code filesDir/models/mnn/<modelName>/}。下载过程中文件先写入
 * {@code filesDir/models/mnn/.tmp_<modelName>/}，全部文件下完且 {@code llm_config.json}
 * 校验通过后，rename 为正式目录（原子完成）。校验失败或中途异常 → 删除 tmp 目录 +
 * 状态置 {@link #STATUS_FAILED}。
 *
 * <p><b>线程模型</b>：所有方法在调用方线程同步执行，内部不创建线程池。
 * 由 {@code DownloadService} 或上层 {@code ioPool} 调度。进度通过 {@link ModelDownloadDao#update}
 * 落库，调用方可轮询 {@link ModelDownloadDao#getAll()} 观察。
 *
 * <p>下载可被 {@link #cancel(String)} 中断：每个模型一个 volatile 标志，下载循环每块读取后检查。
 *
 * <p>status 取值：{@link #STATUS_IDLE} / {@link #STATUS_DOWNLOADING} /
 * {@link #STATUS_PAUSED} / {@link #STATUS_COMPLETED} / {@link #STATUS_FAILED}。
 */
public class ModelDownloadManager {

    /** status 常量。 */
    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /** models 根目录（相对 filesDir）。 */
    private static final String MODELS_REL = "models/mnn";
    /** 下载中临时目录前缀，最终目录名为 {@code .tmp_<modelName>}。 */
    private static final String TMP_PREFIX = ".tmp_";
    /** MNN 模型配置文件名，存在即视为模型目录完整的前提。 */
    private static final String CONFIG_FILE = "llm_config.json";
    /** llm_config.json 中引用的文件字段（实际 MNN 字段名）。 */
    private static final String[] CONFIG_REF_FIELDS = {
            "llm_model", "llm_weight", "embedding_file", "tokenizer_file"
    };

    private static final int BUFFER_SIZE = 8192;
    private static final long PROGRESS_UPDATE_THRESHOLD = 512L * 1024L; // 512KB
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final String SAFE_MODEL_NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,119}";
    /** Remote metadata is input, not authority: keep one market entry bounded before disk I/O. */
    static final int MAX_FILES_PER_MODEL = 4_096;
    static final long MAX_FILE_BYTES = 32L * 1024L * 1024L * 1024L;
    /** Public to the Host catalog projection so the same budget gates listing and installation. */
    public static final long MAX_MODEL_BYTES = 64L * 1024L * 1024L * 1024L;

    private final Context appContext;
    private final ModelDownloadDao dao;

    /** 每个模型一个取消标志；cancel() 置 true，下载循环检查后中断。 */
    private final ConcurrentHashMap<String, Boolean> cancelFlags = new ConcurrentHashMap<>();
    /**
     * A restart/retry can be submitted while cancellation of the previous operation is still
     * being observed.  Serialising each model avoids a new download clearing the old run's cancel
     * marker (and avoids two writers corrupting the same temporary directory).
     */
    private final ConcurrentHashMap<String, ReentrantLock> modelLocks = new ConcurrentHashMap<>();

    public ModelDownloadManager(@NonNull Context appContext, @NonNull ModelDownloadDao dao) {
        this.appContext = appContext.getApplicationContext();
        this.dao = dao;
    }

    // ===== 公开 API =====

    /**
     * 下载一个模型。串行下载仓库内所有文件到 {@code .tmp_<modelName>/}，全部完成后原子
     * rename 为正式目录。中途任何失败（网络/磁盘/校验/取消）→ 删 tmp + 状态 FAILED。
     *
     * @param entry 模型市场条目，必须携带 ModelScope repo
     * @throws IOException          下载失败（含取消，message 含 "cancelled"）
     * @throws IllegalArgumentException entry 无 ModelScope repo
     */
    public void download(@NonNull ModelMarketClient.ModelEntry entry) throws IOException {
        if (entry == null) throw new IllegalArgumentException("entry == null");
        String modelName = entry.modelName;
        requireSafeModelName(modelName);
        String repo = entry.modelScopeRepo;
        if (repo == null || repo.isEmpty()) {
            throw new IllegalArgumentException(
                    "entry has no ModelScope repo: " + modelName);
        }

        ReentrantLock modelLock = lockFor(modelName);
        modelLock.lock();
        try {
        // 清理可能残留的旧 cancel 标志（上次 cancel 时无活动下载 → 标志遗留）。
        // This happens only after a preceding run for this model has released modelLock.
        cancelFlags.remove(modelName);
        Log.i("ModelDownload", "[download] START modelName=" + modelName + " cancelFlags=" + cancelFlags);

        File modelsDir = getModelsDir();
        File tmpDir = new File(modelsDir, TMP_PREFIX + modelName);
        File finalDir = new File(modelsDir, modelName);

        ModelDownloadEntity entity = null;
        try {
            // 1. 尽早建/更新 entity → DOWNLOADING（确保 catch 中 entity 非空）
            entity = dao.getByName(modelName);
            boolean existed = entity != null;
            if (!existed) {
                entity = new ModelDownloadEntity();
                entity.modelName = modelName;
                entity.createdAt = System.currentTimeMillis();
            }
            entity.displayName = entry.description;
            entity.sourceRepo = repo;
            entity.status = STATUS_DOWNLOADING;
            entity.updatedAt = System.currentTimeMillis();
            if (existed) dao.update(entity); else dao.upsert(entity);

            // 2. 列出仓库所有文件
            List<ModelScopeClient.FileInfo> files = ModelScopeClient.listFiles(repo);

            // 3. Validate all remote metadata before creating or appending any model file.
            long totalBytes = checkedTotalBytes(files);
            if (totalBytes == 0 && entry.sizeGb > 0) {
                totalBytes = (long) (entry.sizeGb * 1_000_000_000L);
            }
            entity.totalBytes = totalBytes;
            entity.updatedAt = System.currentTimeMillis();
            dao.update(entity);

            // 4. 磁盘预检
            ensureStorageAllocatable(totalBytes, modelName);

            // 5. 准备 tmp 目录
            if (tmpDir.exists()) {
                // 残留 tmp 保留（断点续传），但若已无 entity 记录此处不会走到
            } else if (!tmpDir.mkdirs() && !tmpDir.exists()) {
                throw new IOException("cannot create tmp dir: " + tmpDir);
            }

            // 6. 串行下载（断点续传 + 失败重试）
            AtomicLong cumulative = new AtomicLong(0);
            for (ModelScopeClient.FileInfo fi : files) {
                if (isCancelled(modelName)) {
                    throw new IOException("cancelled: " + modelName);
                }
                if (fi.path == null || fi.path.isEmpty()) {
                    throw new IOException("market returned a file without path for " + modelName);
                }
                File tmpFile = new File(tmpDir, fi.path);
                ensureWithinDir(tmpFile, tmpDir); // 禁止 path 逃逸
                File parent = tmpFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("cannot create parent dir: " + parent);
                }
                String url = ModelScopeClient.downloadUrl(repo, fi.path);
                downloadFileWithResume(entity, url, tmpFile, fi.size, cumulative, modelName);
            }

            // 7. 校验 llm_config.json + 引用文件
            if (!validateModelDir(tmpDir)) {
                throw new IOException("invalid model dir (llm_config.json check failed): "
                        + modelName);
            }

            // 8. 原子 rename → 正式目录
            if (finalDir.exists()) deleteRecursive(finalDir);
            if (!tmpDir.renameTo(finalDir)) {
                throw new IOException("rename failed: " + tmpDir + " -> " + finalDir);
            }

            // 9. COMPLETED
            entity.downloadedBytes = entity.totalBytes;
            entity.status = STATUS_COMPLETED;
            entity.updatedAt = System.currentTimeMillis();
            dao.update(entity);

        } catch (Exception e) {
            // cancel: 保留 .tmp（断点续传），状态 PAUSED；其他失败: 删 .tmp，状态 FAILED
            boolean isCancelled = e.getMessage() != null && e.getMessage().contains("cancelled");
            if (!isCancelled) {
                deleteRecursive(tmpDir);
            }
            try {
                if (entity == null) {
                    // 理论上不会走到（entity 在 try 顶部即创建），防御性处理
                    entity = dao.getByName(modelName);
                }
                if (entity == null) {
                    entity = new ModelDownloadEntity();
                    entity.modelName = modelName;
                    entity.displayName = entry.description;
                    entity.sourceRepo = repo;
                    entity.createdAt = System.currentTimeMillis();
                }
                entity.status = isCancelled ? STATUS_PAUSED : STATUS_FAILED;
                entity.updatedAt = System.currentTimeMillis();
                if (dao.getByName(modelName) != null) dao.update(entity);
                else dao.upsert(entity);
            } catch (Exception ignored) {
                // 状态落库失败不掩盖原始下载失败
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("download failed: " + modelName + " — " + e.getMessage(), e);
        } finally {
            cancelFlags.remove(modelName);
        }
        } finally {
            modelLock.unlock();
        }
    }

    /**
     * 取消下载：置取消标志（中断活动下载循环）并将已有任务标为 PAUSED。
     * 临时目录保留，因此后续下载能从已写入字节续传。
     */
    public void cancel(@NonNull String modelName) {
        if (!isSafeModelName(modelName)) return;
        Log.w("ModelDownload", "[cancel] CALLED modelName=" + modelName
                + " thread=" + Thread.currentThread().getName()
                + "\n" + android.util.Log.getStackTraceString(new Throwable()));
        cancelFlags.put(modelName, Boolean.TRUE);
        // 不删 .tmp——保留断点供下次续传（之前 cancel 删 .tmp 导致从头下载）
        try {
            ModelDownloadEntity entity = dao.getByName(modelName);
            if (entity != null) {
                entity.status = STATUS_PAUSED;
                entity.updatedAt = System.currentTimeMillis();
                dao.update(entity);
            }
        } catch (Exception ignored) {
            // cancel 必须不抛
        }
    }

    /**
     * 列出本地已下载完成的模型名（{@code filesDir/models/mnn/} 下含 {@code llm_config.json}
     * 的子目录，跳过 {@code .tmp_} 前缀）。字典序排序。
     */
    @NonNull
    public List<String> listLocalModels() {
        File modelsDir = getModelsDir();
        File[] children = modelsDir.listFiles();
        if (children == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (File f : children) {
            if (!f.isDirectory()) continue;
            if (f.getName().startsWith(TMP_PREFIX)) continue;
            if (new File(f, CONFIG_FILE).exists()) {
                result.add(f.getName());
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 模型是否已下载完整（正式目录存在 + {@link #validateModelDir(File)} 通过）。
     */
    public boolean isDownloaded(@NonNull String modelName) {
        if (!isSafeModelName(modelName)) return false;
        File finalDir = new File(getModelsDir(), modelName);
        return finalDir.exists() && validateModelDir(finalDir);
    }

    /**
     * 删除一个模型：递归删正式目录 + .tmp 目录 + DAO 记录。best-effort，不抛。
     *
     * <p>取消标记先于同名锁发布。下载、恢复与删除共用这把公平锁，因此删除不会和下载写入
     * 同一个临时目录竞争，也不会在删除完成后被旧下载重新落盘。</p>
     */
    public void delete(@NonNull String modelName) {
        if (!isSafeModelName(modelName)) return;
        cancelFlags.put(modelName, Boolean.TRUE);
        ReentrantLock modelLock = lockFor(modelName);
        modelLock.lock();
        try {
        File modelsDir = getModelsDir();
        File finalDir = new File(modelsDir, modelName);
        File tmpDir = new File(modelsDir, TMP_PREFIX + modelName);
        deleteRecursive(finalDir);
        deleteRecursive(tmpDir);
        try {
            dao.deleteByName(modelName);
            Log.i("ModelDownload", "[delete] removed " + modelName);
        } catch (Exception e) {
            Log.w("ModelDownload", "[delete] dao.deleteByName failed: " + modelName + " — " + e.getMessage());
        } finally {
            cancelFlags.remove(modelName);
        }
        } finally {
            modelLock.unlock();
        }
    }

    /**
     * 启动期同步 DAO 状态与文件系统——修正 kill/重装/外部清理导致的状态漂移。best-effort，不抛。
     * <ul>
     *   <li>COMPLETED 但正式目录缺失/损坏 → FAILED（文件丢了）；</li>
     *   <li>DOWNLOADING 但正式目录已完整 → COMPLETED（上次 rename 完成但 DAO 没更新）；</li>
     *   <li>DOWNLOADING 但 .tmp 与正式目录都不存在 → FAILED（无可恢复数据）；</li>
     *   <li>DOWNLOADING 且 .tmp 存在、正式目录不存在 → 不改，由 {@link #resumeDownloadIfNeeded()} 转 PAUSED。</li>
     * </ul>
     */
    public void syncDaoWithFileSystem() {
        List<ModelDownloadEntity> all;
        try {
            all = dao.getAll();
        } catch (Exception e) {
            Log.w("ModelDownload", "[sync] dao.getAll failed: " + e.getMessage());
            return;
        }
        File modelsDir = getModelsDir();
        for (ModelDownloadEntity e : all) {
            try {
                File finalDir = new File(modelsDir, e.modelName);
                File tmpDir = new File(modelsDir, TMP_PREFIX + e.modelName);
                boolean finalValid = finalDir.exists() && validateModelDir(finalDir);
                boolean tmpExists = tmpDir.exists();
                String newStatus = null;
                if (STATUS_COMPLETED.equals(e.status) && !finalValid) {
                    newStatus = STATUS_FAILED;
                } else if (STATUS_DOWNLOADING.equals(e.status)) {
                    if (finalValid) {
                        newStatus = STATUS_COMPLETED;
                    } else if (!tmpExists) {
                        newStatus = STATUS_FAILED;
                    }
                    // else (.tmp exists + 无正式目录): 留给 resumeDownloadIfNeeded 转 PAUSED
                }
                if (newStatus != null && !newStatus.equals(e.status)) {
                    e.status = newStatus;
                    e.updatedAt = System.currentTimeMillis();
                    dao.update(e);
                    Log.i("ModelDownload", "[sync] " + e.modelName + " → " + newStatus);
                }
            } catch (Exception ex) {
                Log.w("ModelDownload", "[sync] entry " + e.modelName + " failed: " + ex.getMessage());
            }
        }
    }

    /**
     * 启动期断点续传入口：DOWNLOADING + .tmp 存在 + 正式目录不存在 → 转 PAUSED，
     * 让 UI 显示"继续"按钮（点击重新 {@link #download(ModelMarketClient.ModelEntry)}，
     * downloadFileWithResume 基于 .tmp 已有字节断点续传）。best-effort，不抛。
     */
    public void resumeDownloadIfNeeded() {
        List<ModelDownloadEntity> all;
        try {
            all = dao.getAll();
        } catch (Exception e) {
            Log.w("ModelDownload", "[resume] dao.getAll failed: " + e.getMessage());
            return;
        }
        File modelsDir = getModelsDir();
        for (ModelDownloadEntity e : all) {
            try {
                if (!STATUS_DOWNLOADING.equals(e.status)) continue;
                File tmpDir = new File(modelsDir, TMP_PREFIX + e.modelName);
                File finalDir = new File(modelsDir, e.modelName);
                if (tmpDir.exists() && !finalDir.exists()) {
                    e.status = STATUS_PAUSED;
                    e.updatedAt = System.currentTimeMillis();
                    dao.update(e);
                    Log.i("ModelDownload", "[resume] " + e.modelName + " DOWNLOADING → PAUSED");
                }
            } catch (Exception ex) {
                Log.w("ModelDownload", "[resume] entry " + e.modelName + " failed: " + ex.getMessage());
            }
        }
    }

    // ===== 内部实现 =====

    /** 下载单个文件（断点续传 + 失败重试）。cumulative 累计所有文件已下载字节，用于进度。 */
    private void downloadFileWithResume(@NonNull ModelDownloadEntity entity,
                                        @NonNull String downloadUrl,
                                        @NonNull File tmpFile,
                                        long expectedSize,
                                        @NonNull AtomicLong cumulative,
                                        @NonNull String modelName) throws IOException {
        if (expectedSize < 0 || expectedSize > MAX_FILE_BYTES) {
            throw new IOException("invalid expected file size");
        }
        // 已下载完整 → 直接跳过（避免 Range unsatisfiable 416）
        if (expectedSize > 0 && tmpFile.exists() && tmpFile.length() == expectedSize) {
            cumulative.addAndGet(expectedSize);
            return;
        }

        // 统计本文件 resume 基线（已存在字节）一次
        long fileBaseline = tmpFile.exists() ? tmpFile.length() : 0;
        if (fileBaseline > 0) {
            cumulative.addAndGet(fileBaseline);
            entity.downloadedBytes = cumulative.get();
            entity.updatedAt = System.currentTimeMillis();
            dao.update(entity);
        }

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (isCancelled(modelName)) throw new IOException("cancelled: " + modelName);

            long existing = tmpFile.exists() ? tmpFile.length() : 0;
            if (existing > fileLimit(expectedSize)) {
                throw new IOException("partial file exceeds configured size limit: " + tmpFile.getName());
            }
            Log.i("ModelDownload", "[resume] file=" + tmpFile.getName()
                    + " existing=" + existing + " expected=" + expectedSize);
            HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            try {
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "MatrixAgent/1.0");
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existing + "-");
                }

                int code = conn.getResponseCode();
                Log.i("ModelDownload", "[resume] file=" + tmpFile.getName()
                        + " HTTP=" + code + " existing=" + existing
                        + (code == 206 ? " → APPEND(续传)" : code == 200 && existing > 0 ? " → OVERWRITE(服务端不支持Range!)" : ""));
                // 416 Range Not Satisfiable 且已有字节 → 视为已完成
                if (code == 416 && expectedSize > 0 && existing == expectedSize) {
                    return;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code + " for " + downloadUrl);
                }
                boolean append = (code == 206);
                if (!append && existing > 0) {
                    // 服务端忽略 Range，整文件重下：修正之前计入的基线，避免重复累计
                    cumulative.addAndGet(-existing);
                }
                long remainingLimit = append ? fileLimit(expectedSize) - existing
                        : fileLimit(expectedSize);
                long responseLength = conn.getContentLengthLong();
                if (responseLength > remainingLimit) {
                    throw new IOException("response exceeds configured size limit: " + tmpFile.getName());
                }

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmpFile, append)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    long sinceUpdate = 0;
                    long writtenThisResponse = 0L;
                    while ((read = is.read(buffer)) != -1) {
                        if (isCancelled(modelName)) {
                            Log.e("ModelDownload", "[download] CANCELLED modelName=" + modelName
                                    + " cancelFlags=" + cancelFlags
                                    + " thread=" + Thread.currentThread().getName());
                            throw new IOException("cancelled: " + modelName);
                        }
                        if (writtenThisResponse > remainingLimit - read) {
                            throw new IOException("response exceeds configured size limit: "
                                    + tmpFile.getName());
                        }
                        fos.write(buffer, 0, read);
                        writtenThisResponse += read;
                        cumulative.addAndGet(read);
                        sinceUpdate += read;
                        if (sinceUpdate >= PROGRESS_UPDATE_THRESHOLD) {
                            entity.downloadedBytes = cumulative.get();
                            entity.updatedAt = System.currentTimeMillis();
                            dao.update(entity);
                            sinceUpdate = 0;
                        }
                    }
                    fos.flush();
                }

                // 本文件完成 → flush 进度
                entity.downloadedBytes = cumulative.get();
                entity.updatedAt = System.currentTimeMillis();
                dao.update(entity);
                if (expectedSize > 0L && tmpFile.length() != expectedSize) {
                    throw new IOException("file size mismatch: " + tmpFile.getName());
                }
                return; // 成功
            } catch (IOException e) {
                last = e;
                if (isCancelled(modelName)) throw e;
                // 否则进入下一次重试（resume 从 tmpFile.length() 继续）
            } finally {
                conn.disconnect();
            }
        }
        throw last;
    }

    /**
     * 校验模型目录：{@code llm_config.json} 存在且其引用文件均存在、路径未逃逸。
     * 任何异常 → false（不抛）。
     */
    private boolean validateModelDir(@NonNull File dir) {
        File configFile = new File(dir, CONFIG_FILE);
        if (!configFile.exists() || !configFile.isFile()) return false;
        try {
            ensureWithinDir(configFile, dir);
            String text = new String(Files.readAllBytes(configFile.toPath()), "UTF-8");
            JSONObject config = new JSONObject(text);
            for (String field : CONFIG_REF_FIELDS) {
                if (!config.has(field) || config.isNull(field)) continue;
                String value = config.getString(field);
                if (value == null || value.isEmpty()) continue;
                File referenced = new File(dir, value);
                if (!referenced.exists()) return false;
                ensureWithinDir(referenced, dir); // 禁止 .. 逃逸
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ensures the download peak is allocatable before any model bytes are written.
     *
     * <p>{@link File#getUsableSpace()} is only the presently free space and can under-report
     * space Android may reclaim from caches. On Android O+ we therefore ask StorageManager for
     * allocatable bytes and call allocateBytes only when reclaim is needed. This is a preflight,
     * not a reservation: each write still handles ENOSPC normally. Older devices retain the
     * conservative usable-space fallback.
     */
    private void ensureStorageAllocatable(long contentBytes, String modelName) throws IOException {
        if (contentBytes <= 0L) return;
        long required = withHeadroom(contentBytes);
        File target = appContext.getFilesDir();
        if (target.getUsableSpace() >= required) return;
        StorageManager storage = appContext.getSystemService(StorageManager.class);
        if (storage != null) {
            try {
                UUID uuid = storage.getUuidForPath(target);
                if (storage.getAllocatableBytes(uuid) >= required) {
                    storage.allocateBytes(uuid, required);
                    if (target.getUsableSpace() >= required) return;
                }
            } catch (IOException ignored) {
                // Fall through to one authoritative usable-space check and stable error.
            }
        }
        throw new IOException("INSUFFICIENT_STORAGE: " + modelName);
    }

    private static long withHeadroom(long bytes) {
        if (bytes > Long.MAX_VALUE / 11L) return Long.MAX_VALUE;
        return bytes * 11L / 10L;
    }

    @NonNull
    private File getModelsDir() {
        return new File(appContext.getFilesDir(), MODELS_REL);
    }

    private boolean isCancelled(@NonNull String modelName) {
        return Boolean.TRUE.equals(cancelFlags.get(modelName));
    }

    private ReentrantLock lockFor(@NonNull String modelName) {
        // Fairness ensures a delete already waiting behind an active transfer runs before a
        // later retry for the same model can clear its cancellation marker.
        // The keys originate from the bounded Host catalog. Keeping the lock after completion is
        // intentional: removing an unlocked map entry can split later contenders across two
        // different locks and reintroduce a write/delete race.
        return modelLocks.computeIfAbsent(modelName, ignored -> new ReentrantLock(true));
    }

    static boolean isSafeModelName(String modelName) {
        return modelName != null && modelName.matches(SAFE_MODEL_NAME);
    }

    /** Validates file count, paths and declared byte budget before any disk mutation. */
    static long checkedTotalBytes(List<ModelScopeClient.FileInfo> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IOException("market returned no files");
        }
        if (files.size() > MAX_FILES_PER_MODEL) {
            throw new IOException("market returned too many files");
        }
        long total = 0L;
        for (ModelScopeClient.FileInfo file : files) {
            // A resumable content-addressable model entry must declare a positive byte count.
            // Treating 0 as "unknown" would let a hostile response bypass the aggregate budget.
            if (file == null || file.path == null || file.path.isEmpty()
                    || file.size <= 0L || file.size > MAX_FILE_BYTES) {
                throw new IOException("market returned invalid file metadata");
            }
            try {
                total = Math.addExact(total, file.size);
            } catch (ArithmeticException overflow) {
                throw new IOException("market file sizes overflow", overflow);
            }
            if (total > MAX_MODEL_BYTES) {
                throw new IOException("model exceeds configured size limit");
            }
        }
        return total;
    }

    private static long fileLimit(long expectedSize) {
        return expectedSize > 0L ? expectedSize : MAX_FILE_BYTES;
    }

    private static void requireSafeModelName(String modelName) {
        if (!isSafeModelName(modelName)) {
            throw new IllegalArgumentException("unsafe model name");
        }
    }

    /** child 必须位于 baseDir 内部（canonical 比较，禁止 {@code ..} 逃逸）。 */
    private static void ensureWithinDir(@NonNull File child, @NonNull File baseDir)
            throws IOException {
        String c = child.getCanonicalPath();
        String b = baseDir.getCanonicalPath();
        if (!c.equals(b) && !c.startsWith(b + File.separator)) {
            throw new IOException("path escapes base dir: " + child + " (base=" + baseDir + ")");
        }
    }

    /** 递归删除文件/目录。 */
    private static void deleteRecursive(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
