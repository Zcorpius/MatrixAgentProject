package com.matrix.agent.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * ModelScope API 客户端——列仓库文件 + 构造下载 URL。
 * API 参考 Operit MnnModelDownloadManager。
 */
public final class ModelScopeClient {
    private static final String BASE = "https://modelscope.cn/api/v1/models";

    /** 列出 owner/repo 仓库的所有文件（Recursive）。返回 List<FileInfo>（name/path/size）。 */
    public static List<FileInfo> listFiles(String ownerRepo) throws Exception {
        URL url = new URL(BASE + "/" + ownerRepo + "/repo/files?Recursive=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "MatrixAgent/1.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject data = root.optJSONObject("Data");
            JSONArray files = data != null ? data.optJSONArray("Files") : null;
            List<FileInfo> result = new ArrayList<>();
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String type = f.optString("Type", "");
                    if ("tree".equals(type)) continue; // 跳过目录
                    result.add(new FileInfo(
                            f.optString("Name", ""),
                            f.optString("Path", ""),
                            f.optLong("Size", 0)));
                }
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    /** 构造单文件下载 URL（支持 Range）。 */
    public static String downloadUrl(String ownerRepo, String filePath) {
        if (ownerRepo == null || !ownerRepo.matches("[A-Za-z0-9._-]{1,80}/[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("invalid ModelScope repository");
        }
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("empty ModelScope file path");
        }
        return BASE + "/" + ownerRepo + "/repo?FilePath="
                + encodeUtf8(filePath);
    }

    /**
     * The Charset overload was introduced only in API 33.  This string overload exists on every
     * supported Android release; UTF-8 is mandatory on a Java runtime, so failure is impossible
     * in practice and must not be converted into an unencoded, unsafe URL.
     */
    private static String encodeUtf8(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError("UTF-8 is required by the Java runtime", impossible);
        }
    }

    public static final class FileInfo {
        public final String name;
        public final String path;
        public final long size;

        public FileInfo(String name, String path, long size) {
            this.name = name;
            this.path = path;
            this.size = size;
        }
    }
}
