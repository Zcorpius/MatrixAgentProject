package com.matrix.agent.ondevice.mnn;

import com.matrix.agent.ondevice.GenerationResult;
import com.matrix.agent.ondevice.MnnLoadOptions;
import com.matrix.agent.ondevice.OnDeviceFinishReason;
import com.matrix.agent.ondevice.OnDeviceLlm;

/**
 * {@link OnDeviceLlm} 的 MNN 实现：持有 {@link MNNLlmSession}，把 token 全量缓冲成 String，
 * 用 {@code nativeGetContextInfo} 的 {@code gen_seq_len} 判定 LENGTH（补 Operit 缺的截断检测），
 * 组装 {@link GenerationResult}。
 *
 * <p>generate 是同步阻塞，调用方（OnDeviceModelGateway）负责串行化与线程调度。
 */
public final class MnnOnDeviceLlm implements OnDeviceLlm {

    private final MNNLlmSession session;
    private final int maxAllTokensValue;

    public MnnOnDeviceLlm(MNNLlmSession session, int maxAllTokensValue) {
        this.session = session;
        this.maxAllTokensValue = maxAllTokensValue;
    }

    @Override
    public GenerationResult generate(String messagesJson, String toolsJson, int maxNewTokens,
            java.util.function.BooleanSupplier cancelChecker) {
        if (maxNewTokens <= 0) {
            return GenerationResult.failed("maxNewTokens must be > 0");
        }
        // P0-1: cancel 用 cancelChecker（读 volatile state.cancelled，原子）——不依赖标志绑定
        if (cancelChecker.getAsBoolean()) {
            return new GenerationResult("", OnDeviceFinishReason.CANCELLED, 0, 0, 0, 0, 0, null);
        }
        final StringBuilder out = new StringBuilder();
        boolean ok;
        try {
            ok = session.generateStructured(messagesJson, toolsJson, maxNewTokens, token -> {
                if (cancelChecker.getAsBoolean()) return false;
                out.append(token);
                return true;
            });
        } catch (RuntimeException e) {
            return GenerationResult.failed("native generate threw: " + e.getMessage());
        }
        if (cancelChecker.getAsBoolean()) {
            MNNLlmContextInfo info = session.getContextInfo();
            return new GenerationResult(out.toString(), OnDeviceFinishReason.CANCELLED,
                    info != null ? info.promptLen : 0, info != null ? info.generatedSeqLen : 0,
                    info != null ? info.prefillUs : 0, info != null ? info.decodeUs : 0,
                    info != null ? info.sampleUs : 0, null);
        }
        if (!ok) {
            return GenerationResult.failed("nativeGenerateStreamStructured returned false");
        }

        MNNLlmContextInfo info = session.getContextInfo();
        int gen = info != null ? info.generatedSeqLen : 0;
        // LENGTH 判定：生成达到 maxNewTokens 上限（且 maxNewTokens>0）。否则视为正常 STOP。
        OnDeviceFinishReason fr = (maxNewTokens > 0 && gen >= maxNewTokens)
                ? OnDeviceFinishReason.LENGTH
                : OnDeviceFinishReason.STOP;
        return new GenerationResult(out.toString(), fr,
                info != null ? info.promptLen : 0, gen,
                info != null ? info.prefillUs : 0,
                info != null ? info.decodeUs : 0,
                info != null ? info.sampleUs : 0,
                null);
    }

    @Override
    public int countTokens(String messagesJson, String toolsJson) {
        // P2-17: retry 1 次——瞬时异常(JNI 短暂失败)不应被误判为模板错误(0 → trimToBudget terminal)。
        // 第二次仍抛才返回 0，让上层按模板/配置错误处理。
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return session.countTokensStructured(messagesJson, toolsJson);
            } catch (RuntimeException e) {
                if (attempt == 1) return 0; // 调用方将 0 当模板/配置错误处理，不当"可激进裁剪"
            }
        }
        return 0;
    }

    @Override
    public int maxAllTokens() {
        return maxAllTokensValue;
    }

    @Override
    public boolean isReady() {
        return !session.isReleased();
    }

    @Override
    public void reset() {
        session.reset();
    }

    @Override
    public void cancel() {
        session.cancel(); // nativeCancel 中断 prefill/decode（callback 级停由 cancelChecker 负责）
    }

    @Override
    public void close() {
        session.release();
    }
}
