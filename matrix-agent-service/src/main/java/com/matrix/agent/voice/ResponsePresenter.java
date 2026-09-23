package com.matrix.agent.voice;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.voice.port.*;

import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.ToolObservation;

/**
 * 把 {@link AgentOutcome} 转成可 TTS 播报的 {@link SpeakableResponse}。
 *
 * <p>规则模板生成(非 LLM 改写),终态一次播报——避免现有非流式 ModelGateway 中途输出或被取消时
 * "说半句"。
 *
 * <h2>脱敏白名单(只读这些字段)</h2>
 * <ul>
 *   <li>{@link AgentOutcome#getFinalState()}</li>
 *   <li>{@link Trajectory#countSuccessfulToolCalls()} / {@link Trajectory#getTotalToolCalls()}</li>
 *   <li>{@link ToolObservation#getCapabilityName()} + {@link ToolObservation#isSuccess()}</li>
 * </ul>
 *
 * <p><b>绝不读</b> {@link AgentOutcome#getInternalResults()}、{@code ToolResult.getMessage()}、
 * {@code ToolResult.getObservedState()}、{@code ToolCallSnapshot.getArguments()}——这些含真实 PII
 * (导航目的地 / 联系人 / memory 真实值),不能进 TTS。审计视图 {@link Trajectory} 虽已脱敏,但其
 * 占位符({@code <memory>} / {@code ***} / {@code [redacted:…]})对 TTS 不友好,故本类只取其
 * 聚合计数与 capability id(能力名本身非敏感),自然语言由本类重新生成。
 *
 * @see SpeakableResponse
 * @see CapabilitySpeechNames
 */
public final class ResponsePresenter {
    /** TTS 单次播报字符上限。超出按 {@link #truncate(String, int)} 截断。 */
    static final int MAX_TTS_CHARS = 80;

    /**
     * 生成可播报结果。
     *
     * @param outcome Agent 终态,不能为空
     * @param languageTag BCP-47 语言标签(Demo 固定 zh-CN),不能为空
     */
    public SpeakableResponse present(AgentOutcome outcome, String languageTag) {
        if (outcome == null) throw new IllegalArgumentException("outcome 不能为空");
        if (languageTag == null) throw new IllegalArgumentException("languageTag 不能为空");
        TaskState state = outcome.getFinalState();
        String text = compose(state, outcome.getTrajectory());
        return new SpeakableResponse(truncate(text, MAX_TTS_CHARS), languageTag, state);
    }

    private static String compose(TaskState state, Trajectory trajectory) {
        switch (state) {
            case SUCCEEDED: {
                int success = trajectory.countSuccessfulToolCalls();
                if (success == 1) {
                    String cap = firstSuccessfulCapability(trajectory);
                    String friendly = cap == null ? null : CapabilitySpeechNames.friendlyName(cap);
                    // 命中映射才说"已{友好名}";未知 capability 回退"已完成。",避免说"已这项操作。"
                    if (friendly != null && !friendly.equals(CapabilitySpeechNames.FALLBACK)) {
                        return "已" + friendly + "。";
                    }
                }
                return "已完成。";
            }
            case PARTIALLY_SUCCEEDED: {
                int success = trajectory.countSuccessfulToolCalls();
                int total = trajectory.getTotalToolCalls();
                int failed = total - success;
                if (total > 0 && failed > 0) {
                    return "已完成 " + success + " 项,其余 " + failed + " 项未能完成。";
                }
                return "部分操作已完成。";
            }
            case FAILED:
                return "为安全起见,我现在不能执行这项操作。";
            case NETWORK_UNAVAILABLE:
                return "无法连接云端模型,请检查网络后重试。";
            case REJECTED:
                return "系统繁忙,请稍后重试。";
            case EXECUTION_UNKNOWN:
                return "指令可能已发出,建议查看车辆当前状态。";
            case CANCELLED:
            case PREEMPTED:
                return "已停止。";
            case TIMED_OUT:
                return "操作超时,请重试。";
            case DEFERRED:
                return "稍后再处理。";
            default:
                // 过程态(RECEIVED/UNDERSTANDING/PLANNED/EXECUTING/VERIFYING)不应作为 finalState 出现;
                // 防御性兜底,保守不谎报。
                return "操作未能完成。";
        }
    }

    /** 取第一条成功 ToolObservation 的 capability id。仅用 capability 名与成功标记,不读 result 内容。 */
    private static String firstSuccessfulCapability(Trajectory trajectory) {
        for (AgentIteration iter : trajectory.getIterations()) {
            for (ToolObservation obs : iter.getObservations()) {
                if (obs.isSuccess()) {
                    return obs.getCapabilityName();
                }
            }
        }
        return null;
    }

    /**
     * TTS 文本截断。超长时截到 {@code maxLen-1} 字符并补省略号,保证总长不超过 {@code maxLen}。
     *
     * <p>package-private 以便同包单测直接验证截断契约。
     */
    static String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        if (maxLen <= 1) return text.substring(0, maxLen);
        return text.substring(0, maxLen - 1) + "…";
    }
}
