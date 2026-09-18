package com.matrix.agent.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

/**
 * ModelScope API 客户端——列仓库文件 + 构造下载 URL。
 * API 参考 Operit MnnModelDownloadManager。
 */
public final class ModelScopeClient {
    private static final String BASE = "https://modelscope.cn/api/v1/models";
    private static final String HOST = "modelscope.cn";
    private static final int MAX_FILE_LIST_BYTES = 2 * 1024 * 1024;

    /** Host download path injects its shared controlled client. */
    public static List<FileInfo> listFiles(String ownerRepo, OkHttpClient httpClient) throws Exception {
        requireRepository(ownerRepo);
        if (httpClient == null) throw new IllegalArgumentException("httpClient 不能为空");
        String endpoint = BASE + "/" + ownerRepo + "/repo/files?Recursive=1";
        String response = TrustedHttpsJson.get(endpoint, HOST, "MatrixAgent/0.6", MAX_FILE_LIST_BYTES,
                httpClient);
        {
            JSONObject root = new JSONObject(response);
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
        }
    }

    /** 构造单文件下载 URL（支持 Range）。 */
    public static String downloadUrl(String ownerRepo, String filePath) {
        requireRepository(ownerRepo);
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

    private static void requireRepository(String ownerRepo) {
        if (ownerRepo == null
                || !ownerRepo.matches("[A-Za-z0-9._-]{1,80}/[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("invalid ModelScope repository");
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
