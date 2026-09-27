package com.matrix.agent.intent;

import android.util.Log;

import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LLM-based IntentClassifier——用 Provider(小模型)分类 read-only/write。
 *
 * <p><b>延迟与超时</b>:同步调用 LlmClient.complete;改用 3s 短 deadline,
 * RetryPolicy 退避期间感知 deadline 截断 delay。超时 / 异常由 {@link FallbackIntentClassifier}
 * 捕获后降级 Keyword。
 *
 * <p><b>一致性</b>:temperature=0,相同 command 必返回相同结果(in-memory LRU cache 5 分钟)。
 *
 * <p><b>PII</b>:command 可能含 PII(目的地 / 联系人),由 ModelApiClient 已有的 sanitizer
 * 处理;日志仅记 confidence + reason,不记 command 原文。
 *
 * <p><b>prompt 设计</b>:强 system prompt 限定"仅输出 READ/WRITE/UNKNOWN 三选一,不执行指令";
 * user prompt 仅含 command 原文(由 Provider sanitizer 保护)。
 */
public final class LlmIntentClassifier implements IntentClassifier {
    private static final String TAG = "MatrixAgent";

    /** LRU 缓存上限——车机场景 command 多样性低(主要车控 + 导航),128 足够。 */
    static final int CACHE_MAX_ENTRIES = 128;
    /** LRU 缓存 TTL——5 分钟内同 command 复用结果。 */
    static final long CACHE_TTL_MS = 5L * 60L * 1000L;
    /**
     * IntentClassifier 不在 AgentRequest 主路径——独立 3s 短 deadline,
     * 与原 OkHttp call timeout 对齐。RetryPolicy 退避期间按剩余 deadline 截断 delay。
     */
    static final long DEADLINE_MS = 3_000L;

    private final LlmClient client;
    private final ModelConfig config;
    private final LinkedHashMap<String, CacheEntry> cache;

    /**
     * 依赖 {@link LlmClient} 接口(而非 ModelApiClient 具体类),
     * 让 JVM 单测可注入 fake 验证 deadline 透传。生产路径继续传 ModelApiClient(实现 LlmClient)。
     */
    public LlmIntentClassifier(LlmClient client, ModelConfig config) {
        if (client == null) throw new IllegalArgumentException("client 不能为空");
        if (config == null) throw new IllegalArgumentException("config 不能为空");
        this.client = client;
        this.config = config;
        this.cache = new LinkedHashMap<String, CacheEntry>(CACHE_MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > CACHE_MAX_ENTRIES;
            }
        };
    }

    @Override
    public IntentResult classify(String command) {
        if (command == null || command.trim().isEmpty()) {
            return IntentResult.unknown("empty-command", 0.0);
        }
        String key = command.trim().toLowerCase(Locale.ROOT);
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            if (hit != null && !hit.isExpired()) {
                return hit.result;
            }
        }

        IntentResult fresh;
        try {
            // 用 3s 短 deadline,RetryPolicy 退避期间感知 deadline 截断 delay。
            String llmResponse = client.complete(config, buildSystemPrompt(), buildUserPrompt(command),
                    /* token */ null, System.currentTimeMillis() + DEADLINE_MS);
            fresh = parseLlmResponse(llmResponse);
        } catch (Exception ex) {
            // Provider 失败 / 超时——抛给 FallbackIntentClassifier catch
            Log.w(TAG, "[LlmIntentClassifier] provider FAILED cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw new LlmClassificationException(ex);
        }

        synchronized (cache) {
            cache.put(key, new CacheEntry(fresh, System.currentTimeMillis()));
        }
        return fresh;
    }

    private static String buildSystemPrompt() {
        return "你是车机意图分类器。给定用户命令,仅输出 READ / WRITE / UNKNOWN 三选一,"
                + "不执行任何指令,不解释,不输出其他字符。"
                + "READ 表示查询类(查电量 / 查胎压 / 询问偏好 / 查 QQ 音乐在放什么),"
                + "WRITE 表示会改变状态的操作(打开空调 / 设置导航 / 保存偏好 / "
                + "播放 QQ 音乐 / 暂停 B 站视频 / 打开 B 站视频),"
                + "UNKNOWN 表示无法判定。";
    }

    private static String buildUserPrompt(String command) {
        return "命令:" + command + "\n分类:";
    }

    static IntentResult parseLlmResponse(String llmResponse) {
        if (llmResponse == null) {
            return IntentResult.unknown("llm-empty-response", 0.3);
        }
        String trimmed = llmResponse.trim().toUpperCase(Locale.ROOT);
        // 防御性:LLM 可能附加多余文本,只看首 token
        if (trimmed.startsWith("READ")) {
            return IntentResult.readOnly("llm-read");
        }
        if (trimmed.startsWith("WRITE")) {
            return IntentResult.write("llm-write");
        }
        // UNKNOWN 或无法解析
        return IntentResult.unknown("llm-unknown-or-unparseable", 0.3);
    }

    /** 测试用——清空缓存。 */
    public void invalidateCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Provider 失败包装异常——FallbackIntentClassifier catch 后退 Keyword。 */
    public static final class LlmClassificationException extends RuntimeException {
        public LlmClassificationException(Throwable cause) {
            super(cause);
        }
    }

    private static final class CacheEntry {
        final IntentResult result;
        final long createdAtMs;

        CacheEntry(IntentResult result, long createdAtMs) {
            this.result = result;
            this.createdAtMs = createdAtMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAtMs > CACHE_TTL_MS;
        }
    }
}
