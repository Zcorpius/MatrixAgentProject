package com.matrix.agent.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * MNN 模型市场 JSON 客户端——拉取模型列表 + 缓存到 filesDir。
 * URL: https://meta.alicdn.com/data/mnn/apis/model_market.json
 *
 * <p>The upstream document currently has no publisher signature or per-file digest. Transport is
 * therefore constrained to the exact HTTPS origin and bounded before parsing, but the document
 * must not be represented as an independently signed trust root until upstream/OEM provides that
 * material.</p>
 */
public final class ModelMarketClient {
    private static final String MARKET_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json";
    private static final String MARKET_HOST = "meta.alicdn.com";
    private static final int MAX_MARKET_BYTES = 2 * 1024 * 1024;

    /** Production Host path supplies the process-owned metadata client. */
    public static List<ModelEntry> fetchModels(File cacheFile, OkHttpClient httpClient) throws Exception {
        if (httpClient == null) throw new IllegalArgumentException("httpClient 不能为空");
        String json = null;
        // 尝试网络
        try {
            json = fetchJson(MARKET_URL, httpClient);
            // Never replace a last-known-good market cache until the fresh document can be
            // parsed.  A killed process or captive portal must not turn a usable offline market
            // into a permanently corrupt cache.
            List<ModelEntry> parsed = parseModels(json);
            if (cacheFile != null) {
                try {
                    writeCacheAtomically(cacheFile, json);
                } catch (Exception cacheFailure) {
                    // The validated in-memory result is still safe and useful. Cache durability
                    // is an optimisation, not a reason to fail a user-requested refresh.
                    android.util.Log.w("MatrixAgent", "[ModelMarket] cache update failed",
                            cacheFailure);
                }
            }
            return parsed;
        } catch (Exception networkError) {
            // 网络失败 → 读缓存
            if (cacheFile != null && cacheFile.exists()) {
                json = new String(Files.readAllBytes(cacheFile.toPath()), "UTF-8");
            } else {
                throw networkError;
            }
        }
        return parseModels(json);
    }

    /** 仅读取 Host 私有缓存；供 Binder 的只读目录查询使用，绝不在 Binder 线程联网。 */
    public static List<ModelEntry> loadCachedModels(File cacheFile) throws Exception {
        if (cacheFile == null || !cacheFile.isFile()) return new ArrayList<>();
        return parseModels(new String(Files.readAllBytes(cacheFile.toPath()), "UTF-8"));
    }

    private static List<ModelEntry> parseModels(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray models = root.optJSONArray("models");
        List<ModelEntry> result = new ArrayList<>();
        if (models != null) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                String modelName = m.optString("modelName", "");
                if (modelName.isEmpty()) continue;
                // sources map：取 ModelScope 或第一个
                JSONObject sources = m.optJSONObject("sources");
                String modelScope = sources != null ? sources.optString("ModelScope", "") : "";
                result.add(new ModelEntry(
                        modelName,
                        m.optString("description", modelName),
                        m.optDouble("size_gb", 0),
                        modelScope.isEmpty() ? null : modelScope));
            }
        }
        return result;
    }

    private static String fetchJson(String urlStr, OkHttpClient httpClient) throws Exception {
        return TrustedHttpsJson.get(urlStr, MARKET_HOST, "MatrixAgent/0.6", MAX_MARKET_BYTES,
                httpClient);
    }

    private static void writeCacheAtomically(File destination, String json) throws Exception {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new java.io.IOException("cannot create market cache directory");
        }
        File temporary = File.createTempFile(destination.getName(), ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                // Cache cleanup failure is non-sensitive and cannot affect the validated result.
            }
        }
    }

    /** 模型市场条目。 */
    public static final class ModelEntry {
        public final String modelName;
        public final String description;
        public final double sizeGb;
        public final String modelScopeRepo; // owner/repo（如 MNN/Qwen3-0.6B-MNN），null=无 ModelScope 源

        public ModelEntry(String modelName, String description, double sizeGb, String modelScopeRepo) {
            this.modelName = modelName;
            this.description = description;
            this.sizeGb = sizeGb;
            this.modelScopeRepo = modelScopeRepo;
        }
    }
}
