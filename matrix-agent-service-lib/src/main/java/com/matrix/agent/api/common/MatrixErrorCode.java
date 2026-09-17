package com.matrix.agent.api.common;

/**
 * 集中稳定错误码（跨进程契约的一部分；只追加、不改值、不复用已删除的值）。
 *
 * <p>阶段 A 只收录架构文档已定稿的码；后续新码一律在尾部追加。
 */
public final class MatrixErrorCode {

    public static final int SUCCESS = 0;

    /** 调用方不在 allowlist / 签名不符 / 跨 user 越权。 */
    public static final int PERMISSION_DENIED = 1;
    /** 目标子 Service 尚未就绪（Binder 未注册或正在恢复）。 */
    public static final int SERVICE_NOT_READY = 2;
    /** 相同 clientOperationId 但请求 hash 冲突，目标状态未改变。 */
    public static final int IDEMPOTENCY_CONFLICT = 3;
    /** 只读请求超时。 */
    public static final int TIMED_OUT = 4;
    /** 服务级在途/队列/单 owner 配额超限，任务被拒绝且未入队。 */
    public static final int OVERLOADED = 5;
    /** Service 重启导致只读任务无法恢复。 */
    public static final int SERVICE_RESTARTED = 6;
    /** DEFER 迟到：写命令已下发且不可撤销；任务维持原状态（可能为 EXECUTION_UNKNOWN）。 */
    public static final int TOO_LATE = 7;
    /** SQLCipher/Keystore 不可用，权威持久化整体降级。 */
    public static final int PERSISTENCE_UNAVAILABLE = 8;
    /** DTO violates its documented schema, bounds, or enum domain. */
    public static final int INVALID_ARGUMENT = 9;
    /** Requested owned resource does not exist. */
    public static final int NOT_FOUND = 10;
    /** Endpoint exists but the requested operation is intentionally not supported. */
    public static final int UNSUPPORTED_OPERATION = 11;
    /** Durable task failed after acceptance; inspect its safe event stream for detail. */
    public static final int TASK_FAILED = 12;
    /** Client and service contracts could not negotiate a safe common version. */
    public static final int CONTRACT_MISMATCH = 13;

    private MatrixErrorCode() {
    }
}
