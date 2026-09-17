package com.matrix.agent.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * MNN 模型市场 JSON 客户端——拉取模型列表 + 缓存到 filesDir。
 * URL: https://meta.alicdn.com/data/mnn/apis/model_market.json
 */
public final class ModelMarketClient {
    private static final String MARKET_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json";

    /** 拉取模型市场列表。cacheFile 非 null 时先尝试缓存，网络失败用缓存。 */
    public static List<ModelEntry> fetchModels(File cacheFile) throws Exception {
        String json = null;
        // 尝试网络
        try {
            json = fetchJson(MARKET_URL);
            if (cacheFile != null && json != null) {
                try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                    fos.write(json.getBytes("UTF-8"));
                }
            }
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

    private static String fetchJson(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
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
