package com.matrix.agent.conversation;

import java.util.Collections;
import java.util.List;

/**
 * 能力事实轨迹条目（评估 v1.0 §4.3）：一次 capability 调用的写时净化投影。
 *
 * <p>数据源是结构化执行事实（ToolCallSnapshot 参数 + ToolObservation 结果/readback），
 * 绝不解析模型输出 XML。两段式核验字段固定分离——“请求 X → 核验为 Y”，不一致以
 * Y（readback）为准（音量档位量化、亮度曲线、空调回差是正常设备现实）。</p>
 */
public final class CapabilityExecutionTrace {

    /** 执行结果态（轨迹语义，非消息七态）。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILED = "FAILED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";

    /** 核验态：VERIFIED=读回一致；MISMATCH=读回不一致（以读回为准）；UNAVAILABLE=无读回；UNKNOWN=执行结果未知。 */
    public static final String VERIFY_VERIFIED = "VERIFIED";
    public static final String VERIFY_MISMATCH = "MISMATCH";
    public static final String VERIFY_UNAVAILABLE = "UNAVAILABLE";
    public static final String VERIFY_UNKNOWN = "UNKNOWN";

    public final String capabilityId;
    /** 经 {@code CapabilitySpeechNames} 映射的友好名（未命中回退 capabilityId）。 */
    public final String friendlyName;
    public final String outcome;
    /** 白名单裁剪后的请求参数摘要；可空（无白名单参数）。 */
    public final String requestedDisplay;
    /** 白名单裁剪后的核验值摘要（readback）；可空。 */
    public final String verifiedDisplay;
    public final String verificationState;

    public CapabilityExecutionTrace(String capabilityId, String friendlyName, String outcome,
            String requestedDisplay, String verifiedDisplay, String verificationState) {
        this.capabilityId = requireNonBlank(capabilityId, "capabilityId");
        this.friendlyName = requireNonBlank(friendlyName, "friendlyName");
        this.outcome = requireNonBlank(outcome, "outcome");
        this.requestedDisplay = requestedDisplay;
        this.verifiedDisplay = verifiedDisplay;
        this.verificationState = requireNonBlank(verificationState, "verificationState");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /** 不可变列表便捷构造。 */
    public static List<CapabilityExecutionTrace> listOf(CapabilityExecutionTrace... traces) {
        return traces == null || traces.length == 0
                ? Collections.emptyList() : List.of(traces);
    }
}
