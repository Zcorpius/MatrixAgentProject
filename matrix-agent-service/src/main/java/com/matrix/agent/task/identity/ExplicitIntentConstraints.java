package com.matrix.agent.task.identity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户明确意图约束——由 Runtime 受信任边界(关键词识别 / NLU / 上层调用方)提取,
 * **不能由模型声明**(模型输出不是安全可信来源)。
 *
 * <p>当前范围:只覆盖 zone(关键词识别)。后续扩展到 destination / target / action 等,
 * 由 NLU 服务填充。
 *
 * <p>引入 {@link IntentType} 状态枚举,把"未提及"、"明确单一目标"、
 * "多目标"、"否定冲突"区分开。旧实现把多目标 / 否定约束一律降级为 {@code empty()},
 * 混淆了三种语义,导致"主驾和副驾都调"或"不要调主驾只调副驾"被模型脑补 zone 后放行。
 *
 * <p>当前实现:
 * <ul>
 *   <li>{@link IntentType#UNSPECIFIED} —— 文本未提及任何 zone 关键词,Runtime 不施加约束,
 *       PolicyEngine 走模型 zone 校验路径;</li>
 *   <li>{@link IntentType#SINGLE_TARGET} —— 单一明确目标(zone 有效),PolicyEngine 据此
 *       校验模型 ToolCall 的 zone 与用户意图一致;</li>
 *   <li>{@link IntentType#MULTI_TARGET} —— 文本同时肯定主驾和副驾(如"主驾和副驾都调到24度"),
 *       不支持一句话拆分,PolicyEngine 必须 fail-closed 拒绝;</li>
 *   <li>{@link IntentType#CONFLICT} —— 否定 zone 但无肯定(如"不要调主驾"),或所有提及的 zone
 *       都被否定,PolicyEngine 必须 fail-closed 拒绝。</li>
 * </ul>
 */
public final class ExplicitIntentConstraints {
    public enum IntentType { UNSPECIFIED, SINGLE_TARGET, MULTI_TARGET, CONFLICT }

    /**
     * 主驾关键词:{@code 主驾} 或不在 {@code 副} 之后的 {@code 驾驶位}。
     *
     * <p>"副驾驶位"包含"驾驶位"——用 lookbehind {@code (?<!副)驾驶位} 排除。
     */
    private static final Pattern DRIVER_KEYWORD = Pattern.compile("主驾|(?<!副)驾驶位");

    /** 副驾关键词:{@code 副驾}(覆盖"副驾"、"副驾驶"、"副驾驶位")。 */
    private static final Pattern PASSENGER_KEYWORD = Pattern.compile("副驾");

    /** 否定修饰词。检查 zone 关键词前 8 字符是否含此词,与 AFFIRMATION_TOKENS 比较位置。 */
    private static final String[] NEGATION_TOKENS = {"不要", "别", "勿", "禁"};

    /**
     * 肯定修饰词。{@code 只}/{@code 只要} 出现在否定之后会覆盖否定,标记该 zone 为肯定。
     * 例:"不要调主驾只调副驾" → 副驾前面的"只"在"不要"之后,副驾判为肯定。
     */
    private static final String[] AFFIRMATION_TOKENS = {"只要", "只"};

    private final IntentType intentType;
    private final VehicleZone explicitZone;

    public ExplicitIntentConstraints(IntentType intentType, VehicleZone explicitZone) {
        this.intentType = intentType == null ? IntentType.UNSPECIFIED : intentType;
        this.explicitZone = this.intentType == IntentType.SINGLE_TARGET ? explicitZone : null;
    }

    public static ExplicitIntentConstraints empty() {
        return new ExplicitIntentConstraints(IntentType.UNSPECIFIED, null);
    }

    public static ExplicitIntentConstraints singleTarget(VehicleZone zone) {
        if (zone == null) throw new IllegalArgumentException("SINGLE_TARGET 必须带 zone");
        return new ExplicitIntentConstraints(IntentType.SINGLE_TARGET, zone);
    }

    public static ExplicitIntentConstraints multiTarget() {
        return new ExplicitIntentConstraints(IntentType.MULTI_TARGET, null);
    }

    public static ExplicitIntentConstraints conflict() {
        return new ExplicitIntentConstraints(IntentType.CONFLICT, null);
    }

    public IntentType getIntentType() { return intentType; }

    /**
     * 仅 {@link IntentType#SINGLE_TARGET} 时返回 zone,其他状态返回 null。
     *
     * <p>保留兼容旧调用方——早期代码假设"非 null 即有效"。
     * 新调用方应直接读 {@link #getIntentType()}。
     */
    public VehicleZone getExplicitZone() { return explicitZone; }

    /** 仅 {@link IntentType#SINGLE_TARGET} 时返回 true。 */
    public boolean hasExplicitZone() { return intentType == IntentType.SINGLE_TARGET; }

    /**
     * {@link IntentType#MULTI_TARGET} 或 {@link IntentType#CONFLICT} 时返回 true——
     * PolicyEngine 必须 fail-closed 拒绝执行,不允许降级为无约束(让模型脑补 zone)。
     */
    public boolean isBlocked() {
        return intentType == IntentType.MULTI_TARGET || intentType == IntentType.CONFLICT;
    }

    /**
     * 简单关键词识别——Demo/联调用。后续由 NLU 服务替代。
     *
     * <p>修复要点:
     * <ol>
     *   <li>"副驾驶位"包含"驾驶位"——必须用 lookbehind 排除;</li>
     *   <li>同时肯定主驾和副驾("主驾和副驾都调") → {@link IntentType#MULTI_TARGET};</li>
     *   <li>否定 zone 但无肯定("不要调主驾") → {@link IntentType#CONFLICT};</li>
     *   <li>"不要 X 只 Y"模式("不要调主驾,只调副驾") → {@link IntentType#SINGLE_TARGET}=Y;</li>
     *   <li>MULTI_TARGET / CONFLICT 不再降级为 UNSPECIFIED——PolicyEngine 必须 fail-closed。</li>
     * </ol>
     */
    public static ExplicitIntentConstraints extractFrom(String text) {
        if (text == null || text.isEmpty()) return empty();

        boolean driverMentioned = DRIVER_KEYWORD.matcher(text).find();
        boolean passengerMentioned = PASSENGER_KEYWORD.matcher(text).find();

        if (!driverMentioned && !passengerMentioned) return empty();

        boolean driverNegated = isZoneNegated(text, DRIVER_KEYWORD);
        boolean passengerNegated = isZoneNegated(text, PASSENGER_KEYWORD);

        boolean driverAffirmed = driverMentioned && !driverNegated;
        boolean passengerAffirmed = passengerMentioned && !passengerNegated;

        if (driverAffirmed && passengerAffirmed) {
            return multiTarget();
        }
        if (driverAffirmed) return singleTarget(VehicleZone.DRIVER);
        if (passengerAffirmed) return singleTarget(VehicleZone.PASSENGER);
        // 所有提及的 zone 都被否定 → CONFLICT
        return conflict();
    }

    /**
     * 检查 zone 关键词前 8 个字符中,最近一次出现的修饰词是否定还是肯定。
     *
     * <p>修复要点:
     * <ul>
     *   <li>4 字符 lookback 太短——"不要调主驾和副驾"中"副驾"距"不要"6 字符,会被漏判。</li>
     *   <li>"只"/"只要"出现在否定之后会覆盖否定——"不要调主驾只调副驾"中副驾判为肯定。</li>
     *   <li>8 字符 lookback 仍能正确处理 "调主驾别调副驾"(DRIVER 肯定, PASSENGER 否定)。</li>
     * </ul>
     *
     * <p>判定规则:在 8 字符 lookback 内,
     * <ul>
     *   <li>找最后一个否定 token 的位置 lastNeg;</li>
     *   <li>找最后一个肯定 token 的位置 lastAff;</li>
     *   <li>lastAff &gt; lastNeg → 肯定(返回 false,即"非否定");</li>
     *   <li>lastNeg &gt; lastAff → 否定(返回 true);</li>
     *   <li>两者均未出现 → 非否定。</li>
     * </ul>
     */
    private static boolean isZoneNegated(String text, Pattern zoneKeyword) {
        Matcher m = zoneKeyword.matcher(text);
        while (m.find()) {
            int lookbackStart = Math.max(0, m.start() - 8);
            String prefix = text.substring(lookbackStart, m.start());
            int lastNeg = -1;
            int lastAff = -1;
            for (String token : NEGATION_TOKENS) {
                int p = prefix.lastIndexOf(token);
                if (p > lastNeg) lastNeg = p;
            }
            for (String token : AFFIRMATION_TOKENS) {
                int p = prefix.lastIndexOf(token);
                if (p > lastAff) lastAff = p;
            }
            if (lastAff > lastNeg) return false;  // 肯定覆盖否定
            if (lastNeg > lastAff) return true;   // 否定
        }
        return false;
    }
}
