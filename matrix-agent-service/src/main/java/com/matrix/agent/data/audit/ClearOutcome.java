package com.matrix.agent.data.audit;

/**
 * {@link AuditRepository#clearByUserZone(String, String)} 的结构化返回值。
 *
 * <p>初版让 clearByUserZone 内部 try/catch 吞所有异常,UI 永远显示"已清空"——
 * 指出"偏好和会话已删、Audit 可能仍在,但用户得到完整成功提示"的语义错位。
 *
 * <p>本类把"删了几张表 / 部分失败 / 完全失败 / 不适用"四态显式化,让 Repository
 * 能组合出 {@link com.matrix.agent.data.ClearUserDataOutcome} 透传给 ViewModel,
 * ViewModel 据此选择"已清空" vs "上下文已清,审计删除失败,请稍后重试点击清空"文案。
 *
 * <p><b>fail-open vs fail-closed 边界</b>:
 * <ul>
 *   <li>主流程(MemoryStore / SteerMailbox / Session)失败 → Repository.clearUserDataDetailed
 *       抛 RuntimeException,ViewModel 显示"清空失败 [...]"。fail-closed。</li>
 *   <li>审计维度失败 → 封装进 ClearOutcome 的 PARTIAL_FAILURE / FAILURE 状态,
 *       Repository 不抛(主流程已成功清空偏好 + 会话,不让 audit 失败回滚主流程);
 *       ViewModel 显示"上下文已清,审计删除失败,请稍后重试点击清空"。fail-reporting。</li>
 * </ul>
 *
 * <p><b>计数语义拆字段</b>:初版 {@code success(int tablesCleared)}
 * 把参数同时写进"尝试表数"和"清除表数",而 RoomAuditRepository 传入的其实是删除行数
 * (trajectory 1 行 + session 1 行 + memory 1 行 = 3,不是 3 张表);同一字段在不同调用点
 * 语义不同,UI 后续若按计数渲染就会有歧义。本版拆为 3 个独立字段:
 * <ul>
 *   <li>{@link #tablesAttempted} —— 本次 clearByUserZone 调用尝试清理的表数
 *       (RoomAuditRepository 5 参路径 = 3,单参路径 = 1,Noop / failure = 0)。</li>
 *   <li>{@link #tablesSucceeded} —— 实际删除成功的表数(transaction 提交 = tablesAttempted,
 *       rollback = 0;单参路径 = 1)。</li>
 *   <li>{@link #rowsDeleted} —— 3 表删除的累计行数(trajectory + session + memory 的
 *       deleteByUserZone 返回值求和)。注意 rowsDeleted 与 tablesSucceeded 不可互换:
 *       一个 zone 可能 trajectory 5 行 + session 3 行 + memory 2 行 = 10 行,但只有 3 张表。</li>
 * </ul>
 */
public final class ClearOutcome {
    public enum Status {
        /** 全部目标表已删(3 表 trajectory + session_history + memory_record 都成功)。 */
        SUCCESS,
        /** 部分表删除失败(transaction 回滚或部分 DAO 异常)。 */
        PARTIAL_FAILURE,
        /** 全部表删除失败(database 不可用 / 全部 DAO 抛异常)。 */
        FAILURE,
        /** 实现不支持清理(NoopAuditRepository / 旧版单参 RoomAuditRepository 路径)。 */
        NOT_APPLICABLE
    }

    private final Status status;
    private final String message;
    private final int tablesAttempted;
    private final int tablesSucceeded;
    private final int rowsDeleted;

    private ClearOutcome(Status status, String message,
            int tablesAttempted, int tablesSucceeded, int rowsDeleted) {
        this.status = status;
        this.message = message;
        this.tablesAttempted = tablesAttempted;
        this.tablesSucceeded = tablesSucceeded;
        this.rowsDeleted = rowsDeleted;
    }

    /**
     * 全部目标表清理成功。
     *
     * @param tablesAttempted 本次尝试清理的表数(RoomAuditRepository 5 参路径 = 3,单参路径 = 1)
     * @param tablesSucceeded 实际删成功的表数(success 路径下 = tablesAttempted)
     * @param rowsDeleted     各表 deleteByUserZone 返回值的累计行数
     */
    public static ClearOutcome success(int tablesAttempted, int tablesSucceeded, int rowsDeleted) {
        return new ClearOutcome(Status.SUCCESS, "ok", tablesAttempted, tablesSucceeded, rowsDeleted);
    }

    /**
     * 部分表清理失败(如 transaction 中途异常,但 catch 到时 Room 已 rollback,3 表都没动)。
     */
    public static ClearOutcome partialFailure(int tablesAttempted, int tablesSucceeded,
            int rowsDeleted, String message) {
        return new ClearOutcome(Status.PARTIAL_FAILURE, message,
                tablesAttempted, tablesSucceeded, rowsDeleted);
    }

    public static ClearOutcome failure(String message) {
        return new ClearOutcome(Status.FAILURE, message, 0, 0, 0);
    }

    public static ClearOutcome notApplicable() {
        return new ClearOutcome(Status.NOT_APPLICABLE, "audit clear not supported", 0, 0, 0);
    }

    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public int getTablesAttempted() { return tablesAttempted; }
    public int getTablesSucceeded() { return tablesSucceeded; }
    public int getRowsDeleted() { return rowsDeleted; }

    /** PARTIAL_FAILURE 或 FAILURE 视为 audit 维度失败,ViewModel 应提示用户重试。 */
    public boolean isFailure() {
        return status == Status.PARTIAL_FAILURE || status == Status.FAILURE;
    }

    @Override
    public String toString() {
        return "ClearOutcome{status=" + status
                + " succeeded=" + tablesSucceeded + "/" + tablesAttempted
                + " rows=" + rowsDeleted
                + " msg=" + message + "}";
    }
}
