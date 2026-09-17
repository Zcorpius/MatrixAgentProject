package com.matrix.agent.model;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.CancellableModelCall;
import com.matrix.agent.task.FinishReason;
import com.matrix.agent.task.ModelGateway;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.ModelTurnRequest;
import com.matrix.agent.ondevice.GenerationResult;
import com.matrix.agent.ondevice.OnDeviceFinishReason;
import com.matrix.agent.ondevice.OnDeviceLlm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 端侧本地模型 Gateway：把 {@link ModelGateway} 契约适配到 {@link OnDeviceLlm}（MNN）。
 *
 * <p><b>串行化（P0-5）</b>：MNN session 不能并发生成，{@link #sessionLock} 串行所有 native 调用
 * （MatrixAgent 主驾/副驾 parallelism=2 也会在此排队）。
 *
 * <p><b>per-call cancel（P0-3）</b>：{@link #prepare(ModelTurnRequest)} 每次创建独立 {@link CallState}；
 * {@code abort(state)} 仅标记排队取消，<b>仅当该 call 是当前持锁者</b>（{@link #currentHoldingCall}）
 * 才触发 {@code llm.cancel()}——禁止全局 llm.cancel() 误伤他人。
 *
 * <p><b>lease 退役（P0-2）</b>：{@link #activeLeases} 引用计数；{@link #retire()} 后排队任务取消、
 * 在途任务完成；{@link #awaitDrained(long)} 等归零才允许 {@link #close()}，超时只标 stuck 不 destroy。
 *
 * <p><b>finishReason 映射</b>：LENGTH→{@code ModelTurn.of(text,LENGTH)}（不解析 tool call）；
 * FAILED→抛异常（terminal，不映射 NONE）；CANCELLED 走 abort→ModelCallExecutor cancel 机制（不进本方法）；
 * STOP→parser（fail-closed：protocolError→NONE，对应 AgentEngine PROTOCOL_ERROR 终止）。
 */
public final class OnDeviceModelGateway implements RetirableModelGateway {

    private static final int MIN_PROMPT_TOKENS = 128;

    private final OnDeviceLlm llm;
    private final String displayName;
    private final int maxNewTokensConfig;

    private final ReentrantLock sessionLock = new ReentrantLock();
    private final AtomicInteger activeLeases = new AtomicInteger(0);
    private final Object drainLock = new Object();
    private volatile boolean retired = false;
    private volatile CallState currentHoldingCall = null;
    /** P0-1: 保护 currentHoldingCall 读写 + cancel 的 check+nativeCancel 原子，避免 A 的 cancel 误伤刚拿锁的 B */
    private final Object cancelLock = new Object();
    /**
     * 最后一次端侧推理的性能统计快照（供 UI 展示 token/s + 当前 native heap 采样）。
     * volatile：generate 在 ioPool 线程写、UI 经 Repository 读，需多线程可见。
     * FAILED/CANCELLED 不更新（timing 字段为零/不完整，避免覆盖上次有效快照）。
     */
    private volatile String lastStats = "";

    public OnDeviceModelGateway(OnDeviceLlm llm, String displayName, int maxNewTokensConfig) {
        this.llm = llm;
        this.displayName = displayName;
        this.maxNewTokensConfig = maxNewTokensConfig;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 最后一次推理的性能统计（prefill/decode/tok/s/当前 native heap），UI 读取展示用。
     * 无成功推理前返回空串；FAILED/CANCELLED 不覆盖（保留上次有效快照）。
     */
    public String getLastStats() {
        return lastStats;
    }

    // —— ModelGateway ——

    @Override
    public ModelTurn decide(ModelTurnRequest request) {
        return decideInternal(request, new CallState());
    }

    @Override
    public ExecutionLane executionLane() {
        return ExecutionLane.SERIAL_LOCAL;
    }

    @Override
    public CancellableModelCall prepare(final ModelTurnRequest request) {
        final CallState state = new CallState();
        return new CancellableModelCall() {
            @Override
            public ModelTurn call() {
                return decideInternal(request, state);
            }

            @Override
            public void abort() {
                cancel(state);
            }
        };
    }

    private void cancel(CallState state) {
        // P0-1: check + nativeCancel 原子（cancelLock），避免 A 的 cancel 误伤刚拿锁开始推理的 B
        synchronized (cancelLock) {
            state.cancelled = true;
            if (currentHoldingCall == state) {
                llm.cancel();
            }
        }
    }

    private ModelTurn decideInternal(ModelTurnRequest request, CallState state) {
        if (retired) {
            throw new java.util.concurrent.CancellationException("端侧推理已取消(已退役)");
        }
        activeLeases.incrementAndGet();
        try {
            sessionLock.lockInterruptibly();
            try {
                synchronized (cancelLock) { currentHoldingCall = state; }
                try {
                    // 拿锁后：退役（排队期间被 retire）或被 abort → 取消
                    if (retired || state.cancelled) {
                        // P0-2: 排队/退役 → CANCELLED terminal（不返回 NONE，否则 AgentEngine 误判 PROTOCOL_ERROR）
                        throw new java.util.concurrent.CancellationException(
                                "端侧推理已取消(排队/退役)");
                    }
                    return doDecide(request, state);
                } finally {
                    synchronized (cancelLock) { currentHoldingCall = null; }
                }
            } finally {
                sessionLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("端侧推理线程中断");
        } finally {
            if (activeLeases.decrementAndGet() == 0) {
                synchronized (drainLock) {
                    drainLock.notifyAll();
                }
            }
        }
    }

    /** 实际推理 + 映射（在 sessionLock 内，在途，不查 retired）。 */
    private ModelTurn doDecide(ModelTurnRequest request, CallState state) {
        try {
            if (state.cancelled || retired) {
                throw new java.util.concurrent.CancellationException("端侧推理已取消(codec 前)");
            }
            Map<String, String> capToModel = MnnStructuredRequestCodec.capabilityToModelName(request.getTools());
            Map<String, String> modelToCap = MnnStructuredRequestCodec.modelToCapability(request.getTools());
            String toolsJson = MnnStructuredRequestCodec.buildToolsJson(request.getTools());

            int maxAll = llm.maxAllTokens();
            if (maxAll <= MIN_PROMPT_TOKENS) {
                throw new RuntimeException("端侧模型 max_all_tokens 过小: " + maxAll);
            }
            int maxNew = Math.min(maxNewTokensConfig, maxAll - MIN_PROMPT_TOKENS);
            int maxPromptTokens = maxAll - maxNew;

            List<AgentMessage> conv = trimToBudget(request.getConversation(), capToModel, toolsJson, maxPromptTokens);
            String messagesJson = MnnStructuredRequestCodec.buildMessagesJson(conv, capToModel);

            // P0-1: generate 前再检查——cancel 若在拿锁检查后、generate 启动前到达，此处拦截（不跑 generate，lease 不占）
            if (state.cancelled || retired) {
                throw new java.util.concurrent.CancellationException(
                        "端侧推理已取消(generate 前检查)");
            }
            GenerationResult r = llm.generate(messagesJson, toolsJson, maxNew,
                    () -> state.cancelled || retired);
            // generate 后再检查（cancel 可能在 generate 中到达、currentCancelFlag 绑定前丢失）
            if (state.cancelled || retired) {
                throw new java.util.concurrent.CancellationException(
                        "端侧推理已取消(generate 后检查)");
            }
            // 在 mapResult 前记录 timing——异常/cancel 路径已在上面抛出，不会到达此处；
            // FAILED/CANCELLED finishReason 由 updateStats 内部跳过（不覆盖上次有效快照）。
            updateStats(r);
            return mapResult(r, modelToCap);
        } catch (RuntimeException e) {
            throw e; // terminal
        } catch (Exception e) {
            throw new RuntimeException("端侧 decide 失败: " + e.getMessage(), e);
        }
    }

    private ModelTurn mapResult(GenerationResult r, Map<String, String> modelToCap) {
        if (r.finishReason == OnDeviceFinishReason.FAILED) {
            throw new RuntimeException("端侧推理失败: " + r.nativeError); // terminal，不映射 NONE
        }
        if (r.finishReason == OnDeviceFinishReason.CANCELLED) {
            // P0-C: 取消抛 CancellationException → ModelCallExecutor 映射 CANCELLED terminal
            // （不返回 NONE，否则 AgentEngine 误判 PROTOCOL_ERROR；CANCELLED 不是协议错误）
            throw new java.util.concurrent.CancellationException("端侧推理已取消");
        }
        if (r.finishReason == OnDeviceFinishReason.LENGTH) {
            // 截断：不解析 tool call（可能是半截 JSON）
            String text = r.text == null ? "" : r.text;
            return ModelTurn.of(text, FinishReason.LENGTH); // → AgentEngine LENGTH_EXCEEDED 终止
        }
        // STOP
        OnDeviceToolCallParser.ParseResult pr = OnDeviceToolCallParser.parse(r.text, modelToCap);
        if (pr.protocolError) {
            return ModelTurn.of(pr.textContent, FinishReason.NONE); // fail-closed → PROTOCOL_ERROR 终止
        }
        if (!pr.calls.isEmpty()) {
            return ModelTurn.ofToolCalls(pr.calls, pr.textContent);
        }
        String text = pr.textContent;
        if (text == null || text.trim().isEmpty()) {
            return ModelTurn.of("", FinishReason.NONE); // 空答复 → PROTOCOL_ERROR
        }
        return ModelTurn.directAnswer(text);
    }

    /**
     * 把 {@link GenerationResult} 的 timing + token 统计格式化为 UI 可读字符串，写入 {@link #lastStats}。
     *
     * <p>调用点：{@code doDecide} 内 {@code llm.generate()} 返回 + 取消检查通过后、{@code mapResult} 前——
     * 这样 generate 抛异常或被 cancel 时不会到达本方法。{@code FAILED}/{@code CANCELLED} finishReason
     * 的 timing 字段为零/不完整，显式跳过以保留上次有效快照。
     *
     * <p>tps 由 {@code generatedTokens / decodeSec} 推导（decode 是逐 token 生成的稳态阶段）；
     * native heap 取 {@code Debug.getNativeHeapAllocatedSize}（MNN session + KV cache 占用主体）。
     */
    private void updateStats(GenerationResult r) {
        if (r == null) return;
        if (r.finishReason == OnDeviceFinishReason.FAILED
                || r.finishReason == OnDeviceFinishReason.CANCELLED) {
            return;
        }
        double prefillSec = r.prefillUs / 1_000_000.0;
        double decodeSec = r.decodeUs / 1_000_000.0;
        double tps = decodeSec > 0 ? r.generatedTokens / decodeSec : 0;
        long nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
        lastStats = String.format(Locale.US,
                "prefill %.2fs | decode %.2fs | %d tokens | %.1f tok/s | native heap %dMB",
                prefillSec, decodeSec, r.generatedTokens, tps, nativeHeapMB);
    }

    /**
     * token 预算裁剪（P0-6）：用 {@code llm.countTokens}（含 tools 开销），按 "system + 完整 assistant/tool group"
     * 为最小单元从队首丢，绝不裁在一组 tool call 中间。{@code countTokens==0} 视为模板错误（不裁，让上层报错）。
     */
    private List<AgentMessage> trimToBudget(List<AgentMessage> conv, Map<String, String> capToModel,
            String toolsJson, int maxPromptTokens) {
        int head = 0;
        if (!conv.isEmpty() && conv.get(0).getRole() == AgentMessage.Role.SYSTEM) head = 1;
        List<AgentMessage> systemHead = head > 0 ? new ArrayList<>(conv.subList(0, head)) : Collections.<AgentMessage>emptyList();
        List<AgentMessage> rest = conv.subList(head, conv.size());
        List<List<AgentMessage>> groups = groupify(rest);

        // 不超预算则全保留
        int fullCount = countWith(systemHead, groups, 0, capToModel, toolsJson);
        if (fullCount == 0) {
            throw new RuntimeException("端侧 countTokens==0（chat_template/模型配置错误）");
        }
        if (fullCount <= maxPromptTokens) {
            return conv;
        }

        int keepFrom = 0;
        while (keepFrom < groups.size()) {
            int count = countWith(systemHead, groups, keepFrom, capToModel, toolsJson);
            if (count == 0) {
                throw new RuntimeException("端侧 countTokens==0（chat_template/模型配置错误）");
            }
            if (count <= maxPromptTokens) break;
            keepFrom++;
        }
        List<AgentMessage> result = new ArrayList<>(systemHead);
        for (int i = keepFrom; i < groups.size(); i++) result.addAll(groups.get(i));
        if (keepFrom >= groups.size()) {
            int sysCount = countWith(systemHead, Collections.<List<AgentMessage>>emptyList(), 0, capToModel, toolsJson);
            if (sysCount > maxPromptTokens) {
                throw new RuntimeException("端侧 system prompt 独自超出 token 预算");
            }
        }
        return result;
    }

    private int countWith(List<AgentMessage> systemHead, List<List<AgentMessage>> groups, int keepFrom,
            Map<String, String> capToModel, String toolsJson) {
        List<AgentMessage> candidate = new ArrayList<>(systemHead);
        for (int i = keepFrom; i < groups.size(); i++) candidate.addAll(groups.get(i));
        try {
            String messagesJson = MnnStructuredRequestCodec.buildMessagesJson(candidate, capToModel);
            return llm.countTokens(messagesJson, toolsJson);
        } catch (Exception e) {
            return 0;
        }
    }

    /** group 化：ASSISTANT(带 tool_calls) + 紧随的 TOOL 为一个不可分组；其余各自成组。 */
    private List<List<AgentMessage>> groupify(List<AgentMessage> rest) {
        List<List<AgentMessage>> groups = new ArrayList<>();
        int i = 0;
        while (i < rest.size()) {
            AgentMessage m = rest.get(i);
            List<AgentMessage> g = new ArrayList<>();
            g.add(m);
            if (m.getRole() == AgentMessage.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                int j = i + 1;
                while (j < rest.size() && rest.get(j).getRole() == AgentMessage.Role.TOOL) {
                    g.add(rest.get(j));
                    j++;
                }
                i = j;
            } else {
                i++;
            }
            groups.add(g);
        }
        return groups;
    }

    // —— RetirableModelGateway ——

    @Override
    public void retire() {
        retired = true;
        // 中断可能在跑的（让在途尽快返回后 lease 归零）；排队任务拿锁后自检 retired 被取消
        llm.cancel();
    }

    @Override
    public boolean isDrained() {
        return activeLeases.get() == 0;
    }

    @Override
    public boolean awaitDrained(long timeoutMillis) throws InterruptedException {
        synchronized (drainLock) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (activeLeases.get() > 0) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false; // 超时，未 drained——调用方标 stuck，绝不 destroy
                drainLock.wait(left);
            }
            return true;
        }
    }

    @Override
    public void close() {
        llm.close();
    }

    /** 单次调用的取消状态（per-call cancel，P0-3）。 */
    private static final class CallState {
        volatile boolean cancelled = false;
    }
}
