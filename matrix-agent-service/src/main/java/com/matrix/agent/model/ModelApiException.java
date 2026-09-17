package com.matrix.agent.model;

/**
 * 模型 API 调用异常基类——按 HTTP 状态码 / 错误类型分类,
 * 让 {@link ModelApiClient} 内部 {@link RetryPolicy} 决策是否重试。
 *
 * <p><b>getMessage() 不泄露 HTTP body / endpoint</b>——API key / 用户 PII 可能被网关回显。
 * 仅返回错误类型 + sanitized endpoint(去 query string)。
 *
 * <p>子类:
 * <ul>
 *   <li>{@link RateLimitException}:HTTP 429——服务端限流,RetryPolicy 重试(指数退避);</li>
 *   <li>{@link ServerException}:HTTP 5xx——服务端临时故障,RetryPolicy 重试;</li>
 *   <li>{@link ClientException}:HTTP 4xx(除 429)——客户端错误(API key 错 / 参数错),
 *       不重试;</li>
 *   <li>{@link NetworkException}:网络层异常(IOException / UnknownHost),不重试
 *       (RetryPolicy 仅对已知临时故障重试,网络抖动按 TIMEOUT 处理走 LLM 转换终态);</li>
 *   <li>{@link TimeoutException}:OkHttp call timeout,不重试(超时通常是 prompt 太长 /
 *       Provider 慢,重试只会再次超时)。</li>
 * </ul>
 */
public abstract class ModelApiException extends RuntimeException {
    /** HTTP 状态码(0 表示非 HTTP 错误,如网络 / 超时)。 */
    public final int statusCode;
    /** Sanitized endpoint(去 query string,去 apiKey 参数)——仅诊断用。 */
    public final String sanitizedEndpoint;

    protected ModelApiException(String type, int statusCode, String sanitizedEndpoint, Throwable cause) {
        super(type + " status=" + statusCode + " endpoint=" + sanitizedEndpoint, cause);
        this.statusCode = statusCode;
        this.sanitizedEndpoint = sanitizedEndpoint;
    }

    /** HTTP 429——服务端限流。 */
    public static final class RateLimitException extends ModelApiException {
        public RateLimitException(String sanitizedEndpoint, Throwable cause) {
            super("rate-limit", 429, sanitizedEndpoint, cause);
        }

        @Override public boolean isRetryable() { return true; }
    }

    /** HTTP 5xx——服务端临时故障。 */
    public static final class ServerException extends ModelApiException {
        public ServerException(int statusCode, String sanitizedEndpoint, Throwable cause) {
            super("server-error", statusCode, sanitizedEndpoint, cause);
        }

        @Override public boolean isRetryable() { return true; }
    }

    /** HTTP 4xx(除 429)——客户端错误,不重试。 */
    public static final class ClientException extends ModelApiException {
        public ClientException(int statusCode, String sanitizedEndpoint, Throwable cause) {
            super("client-error", statusCode, sanitizedEndpoint, cause);
        }
    }

    /** 网络层异常——不重试,AgentEngine 转 TIMEOUT 终态。 */
    public static final class NetworkException extends ModelApiException {
        public NetworkException(String sanitizedEndpoint, Throwable cause) {
            super("network-error", 0, sanitizedEndpoint, cause);
        }
    }

    /** OkHttp call timeout——不重试,AgentEngine 转 TIMEOUT 终态。 */
    public static final class TimeoutException extends ModelApiException {
        public TimeoutException(String sanitizedEndpoint, Throwable cause) {
            super("timeout", 0, sanitizedEndpoint, cause);
        }
    }

    /**
     * 是否可重试——RetryPolicy 决策依据。
     *
     * <p>子类按"该错误类型是否值得重试"覆盖:
     * {@code RateLimitException} 与 {@code ServerException} 可重试;其他不重试。
     */
    public boolean isRetryable() {
        return false;
    }
}
