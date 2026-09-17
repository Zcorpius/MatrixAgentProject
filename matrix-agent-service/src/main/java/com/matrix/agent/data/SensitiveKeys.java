package com.matrix.agent.data;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 业务 PII key 共享 denylist。
 *
 * <p>两个调用方共享,避免双份维护漂移:
 * <ul>
 *   <li>{@link com.matrix.agent.task.prompt.DefaultPromptBuilder} —— 投影记忆 snippet 到 prompt 时,
 *       PII key 的 value 永不附(仅 "已保存,请用工具查询")。</li>
 *   <li>{@link com.matrix.agent.task.AuditRedactor} —— redactArguments 命中 PII key 时
 *       value 替换为 {@code <memory>}(key 本身是元数据,不 mask)。</li>
 * </ul>
 *
 * <p><b>denylist 而非 allowlist</b>:没法穷举所有合法 preference key(用户/车型/地区差异),
 * allowlist 会逼业务方申请审批——denylist 默认放行,已知 PII key 列入。
 *
 * <p><b>后续版本切 capability schema isSensitive() 后本类可移除</b>:届时 PII 由
 * schema 元数据自动判定,不再依赖静态 key 列表。
 */
public final class SensitiveKeys {
    /** 业务 PII key 黑名单 —— DefaultPromptBuilder 投影 + AuditRedactor 共享。 */
    public static final Set<String> BUILTIN_PII_KEYS;

    static {
        Set<String> keys = new HashSet<>();
        // 地址类
        keys.add("home_address");
        keys.add("work_address");
        keys.add("contact_address");
        // 联系方式
        keys.add("contact_phone");
        keys.add("contact_email");
        // 证件 / 金融
        keys.add("id_card");
        keys.add("passport_no");
        keys.add("credit_card");
        keys.add("bank_account");
        // 医疗
        keys.add("medical_condition");
        keys.add("allergy_detail");
        BUILTIN_PII_KEYS = Collections.unmodifiableSet(keys);
    }

    /**
     * 判断 key 是否命中 PII denylist(大小写不敏感)。
     *
     * @param key 待判定的 memory key,可空——null 返回 false
     */
    public static boolean isPiiKey(String key) {
        return key != null && BUILTIN_PII_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    private SensitiveKeys() { }
}
