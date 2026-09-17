package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.ToolDefinition;
import com.matrix.agent.task.capability.ToolParameterDefinition;
import com.matrix.agent.task.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计侧 Observation 脱敏器——喂 {@link Trajectory} / UI / Log,**字段级脱敏**。
 *
 * <p>数据边界(修复后的正确方向):
 * <ul>
 *   <li>Conversation(模型侧):走 {@link ModelSanitizer},保留任务语义。</li>
 *   <li>Trajectory / UI / Log(本类处理):字段级脱敏——memory.preference.* 的 value
 *       一律替换为 {@code <memory>},message 替换为 {@link #MEMORY_MESSAGE_PLACEHOLDER}。</li>
 * </ul>
 *
 * <p>"用户看自己的偏好不是隐私"在 AAOS 不是通用假设——主驾副驾可能共用 Android User,
 * UI 查看者未必是数据所有者,持久化后暴露面更大。
 *
 * <p>本类还做超长截断({@code maxChars})。
 */
public final class AuditRedactor {
    private static final String TAG = "MatrixAgent";
    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("AIza[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{16,}"),
            Pattern.compile("(?i)Bearer\\s+\\S+")
    };
    /** 把整条 observation 全脱敏的 capability 集合——value 不该进 Trajectory/UI/Log。 */
    private static final Set<String> FULL_REDACT_CAPABILITIES = new HashSet<>(Arrays.asList(
            "memory.preference.save", "memory.preference.get",
            "memory.semantic.save", "memory.semantic.get"));

    /** memory.preference.* observation 脱敏后的 message 占位符,测试与 UI 渲染都会用。 */
    public static final String MEMORY_MESSAGE_PLACEHOLDER = "[user memory preference redacted]";

    /**
     * capability 已声明 Audit Schema(auditMessageTemplate 非空)但未配置
     * {@code auditFailureMessageTemplate} 时,失败状态 message 的兜底占位符。
     *
     * <p>不能 fall through 到 {@link #redact(String)} ——通用凭据正则识别不出业务值
     * (目的地 / 地址 / 联系人),Provider 失败 message 会把真实业务值写入 Trajectory/UI/Log。
     */
    public static final String FAILURE_MESSAGE_FALLBACK = "[capability failed: details redacted]";

    /**
     * 即使 schema 没标 sensitive,这些字段名也无条件视为凭据/敏感字段。
     * 大小写不敏感匹配。这是 schema 元数据缺失时的兜底。
     *
     * <p>扩充业务 PII key(home_address / contact_phone / id_card 等),
     * 与 {@link com.matrix.agent.data.SensitiveKeys#BUILTIN_PII_KEYS} 共享 denylist。
     * 命中 PII key 时 value 替换为 {@code <memory>}(语义同 preference);
     * key 本身是元数据,不 mask。
     *
     * <p>注意:不包括 {@code key} 这种过于通用的字段名——memory.preference 等业务场景下
     * key 是偏好名(preferred_temperature / home_address),不能误判为凭据。
     */
    private static final Set<String> BUILTIN_CREDENTIAL_KEYS;
    static {
        Set<String> keys = new HashSet<>(Arrays.asList(
                "api_key", "apikey", "token", "access_token", "refresh_token", "id_token",
                "authorization", "auth", "bearer", "secret", "client_secret",
                "password", "passwd", "credential", "credentials"));
        // 业务 PII key 共享 denylist
        keys.addAll(com.matrix.agent.data.SensitiveKeys.BUILTIN_PII_KEYS);
        BUILTIN_CREDENTIAL_KEYS = keys;
    }

    private final int maxChars;
    private final CapabilityRegistry capabilityRegistry;
    /**
     * 自由文本摘要策略——volatile 让 AppContainer 装配期
     * {@link #setDigest(AuditDigest)} 注入对其他线程立即可见(AgentEngine 工作线程
     * 可能在注入前已经构造,但实际 digest() 调用在任务执行期,远晚于装配)。
     *
     * <p>默认 {@link UnavailableAuditDigest}——fail-closed:未注入时输出固定
     * {@code [redacted:chars=N,digest=unavailable]},不暴露"同输入同输出"的稳定性信号,
     * 杜绝 SHA-1 8 位对低熵文本(电话号码 / 地址 / 固定指令)的枚举反推。
     *
     * <p>评审指出:初版默认 {@link Sha1AuditDigest} + {@code setDigest(null)}
     * 回退 SHA-1,虽然当前 AppContainer 走 {@code KeystoreHmacAuditDigest} 装配路径不会触发,
     * 但未来遗漏 HMAC 注入(JVM 测试 fake / 新装配路径遗漏 setDigest 调用)时会**静默**降低
     * 隐私级别——而 SHA-1 8 位正是升级要修的洞。修复:生产默认与 null 兜底都改 fail-closed,
     * 仅显式 {@code setDigest(new Sha1AuditDigest())} 时才走 SHA-1(旧版兼容契约由
     * {@code AuditFreeTextRedactionTest.sha1DigestOptInViaSetDigest} 显式注入验证)。
     */
    private volatile AuditDigest digest = new UnavailableAuditDigest();

    public AuditRedactor(int maxChars) {
        this(maxChars, null);
    }

    /** 注入 CapabilityRegistry 后,redactArguments(cap, args) 可以按 schema 脱敏。 */
    public AuditRedactor(int maxChars, CapabilityRegistry registry) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars 必须大于 0");
        this.maxChars = maxChars;
        this.capabilityRegistry = registry;
    }

    /**
     * AppContainer 装配期注入 digest 策略。
     *
     * <p>调用契约:仅在 AgentEngine 构造后立即调用(AppContainer engineFactory lambda 内),
     * 之后任务执行期不再变更。多次调用幂等(后写覆盖前写)。
     *
     * @param digest null 时退化为 {@link UnavailableAuditDigest}(fail-closed,
     *              不再退回旧 SHA-1——避免遗漏 HMAC 注入时静默降低隐私级别)。
     *              需旧 SHA-1 兼容行为的测试请显式传入 {@code new Sha1AuditDigest()}。
     */
    public void setDigest(AuditDigest digest) {
        this.digest = digest == null ? new UnavailableAuditDigest() : digest;
        Log.i(TAG, "[Audit] digest strategy set to " + this.digest.getClass().getSimpleName()
                + " prefix=" + this.digest.prefix());
    }

    public String redact(String content) {
        if (content == null) return "";
        String result = content;
        int totalMasked = 0;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher m = pattern.matcher(result);
            int hits = 0;
            while (m.find()) hits++;
            if (hits > 0) {
                result = m.replaceAll("***");
                totalMasked += hits;
            }
        }
        boolean truncated = result.length() > maxChars;
        if (truncated) {
            result = ModelSanitizer.truncateWithSuffix(result, maxChars);
        }
        if (totalMasked > 0 || truncated) {
            Log.d(TAG, "[Audit] applied secretMask=" + totalMasked
                    + " truncated=" + truncated
                    + " origChars=" + content.length() + " redactedChars=" + result.length());
        }
        return result;
    }

    /**
     * 自由文本 fail-closed —— assistant content / 未配置 audit template 的
     * capability message 一律用 {@code "[redacted:chars=N,<prefix>=<hex|unavailable>]"}
     * 替代原文。
     *
     * <p>保留 chars 是因为长度本身是诊断信号(max_tokens 截断 / 异常长输入);
     * digest 字段允许事后按已知明文比对 ("这条审计行对应的是否是
     * '把主驾温度调到 24 度'"),但不可逆推原文。
     *
     * <p>digest 字段三种语义:
     * <ul>
     *   <li>{@code hmac=16hex} —— AppContainer 装配 {@link KeystoreHmacAuditDigest} 后的
     *       生产主路径(HMAC-SHA-256 截断 16 位,64-bit 抗碰撞,Keystore 派生密钥)。</li>
     *   <li>{@code sha=8hex} —— 显式 {@code setDigest(new Sha1AuditDigest())} 注入的
     *       旧版兼容路径;仅用于历史契约测试,生产不再走。</li>
     *   <li>{@code digest=unavailable} —— **默认值** + {@code setDigest(null)} + AppContainer
     *       装配失败兜底。fail-closed:任何输入产出相同摘要,杜绝"同输入同输出"枚举反推。</li>
     * </ul>
     *
     * <p>原 {@link #redact(String)} 路径保留——仍是 Tool arguments / ToolResult message
     * 的默认处理 (凭据正则 + 截断),不破坏旧测试。
     */
    public String redactFreeText(String content) {
        if (content == null) return "";
        int chars = content.length();
        String hash = digest.digest(content);
        return "[redacted:chars=" + chars + "," + digest.prefix() + "=" + hash + "]";
    }

    /**
     * 递归脱敏 Map——嵌套 Map / List 都会逐层处理,String value 过 secret mask。
     * 不识别业务敏感字段(destination=家 等)——另加 Capability Schema 级敏感字段元数据。
     */
    public Map<String, Object> redactMap(Map<String, Object> observed) {
        return redactMapRecursive(observed);
    }

    /**
     * 给 Tool Arguments 用——同 redactMap,只是语义上更明确(参数也是字段级脱敏对象)。
     * 不感知 capability,只跑递归凭据正则;调用方需要 schema 级脱敏时改用
     * {@link #redactArguments(String, Map)}。
     */
    public Map<String, Object> redactArguments(Map<String, Object> args) {
        return redactMapRecursive(args);
    }

    /**
     * 按 capability schema 脱敏 arguments,fail-closed。
     *
     * <p>处理顺序:
     * <ol>
     *   <li>registry 为空(legacy 调用方):走 {@link #redactArguments(Map)}(只有凭据正则)。</li>
     *   <li>capability 不在 registry / 被 R3 阻挡(findToolDefinition == null):
     *       <b>fail-closed</b>——所有 value 替换为 {@code ***}。
     *       未注册 capability 要么是模型脑补,要么是 R3 被禁用的——两种都不该让 args 原文进 audit。</li>
     *   <li>capability 在 registry 中:对每个 entry,
     *       <ul>
     *         <li>schema 标 sensitive → 替换为 {@link ToolParameterDefinition#getSensitivePlaceholder()}</li>
     *         <li>key 命中 builtin 凭据名(大小写不敏感) → 替换为 {@code ***}</li>
     *         <li>key 不在 schema parameters(strict schema 外的额外字段) → 替换为 {@code ***}
     *             <b>fail-closed</b>——模型多塞字段违反 strict schema,值不该进 audit。</li>
     *         <li>否则递归 redactValue(原有凭据正则 + 截断)</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>导航 destination、memory preference value/key 等业务敏感字段在 Audit 视图
     * 不再以原值出现;未注册 / R3 / 额外字段全部 fail-closed。
     */
    public Map<String, Object> redactArguments(String capabilityName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return Collections.emptyMap();
        if (capabilityRegistry == null) return redactArguments(args);
        ToolDefinition tool = findToolDefinition(capabilityName);
        if (tool == null) {
            // 未注册 / R3 capability → fail-closed,所有 value 一律 mask。
            Log.w(TAG, "[Audit] capability=" + capabilityName
                    + " not in registry (unregistered or R3-blocked) — fail-closed mask all args");
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (String key : args.keySet()) redacted.put(key, "***");
            return redacted;
        }

        Map<String, String> sensitivePlaceholders = new LinkedHashMap<>();
        Set<String> schemaParameterNames = new HashSet<>();
        for (ToolParameterDefinition param : tool.getParameters()) {
            if (param.isSensitive()) {
                sensitivePlaceholders.put(param.getName(), param.getSensitivePlaceholder());
            }
            schemaParameterNames.add(param.getName());
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (sensitivePlaceholders.containsKey(key)) {
                redacted.put(key, sensitivePlaceholders.get(key));
            } else if (isBuiltinCredentialKey(key)) {
                redacted.put(key, "***");
            } else if (!schemaParameterNames.contains(key)) {
                // schema 外的额外字段 fail-closed。
                Log.w(TAG, "[Audit] capability=" + capabilityName
                        + " arg key=\"" + key + "\" not in schema — fail-closed mask");
                redacted.put(key, "***");
            } else {
                redacted.put(key, redactValue(value));
            }
        }
        return redacted;
    }

    private ToolDefinition findToolDefinition(String capabilityName) {
        if (capabilityRegistry == null) return null;
        for (ToolDefinition tool : capabilityRegistry.toToolDefinitions()) {
            if (tool.getCapabilityName().equals(capabilityName)) return tool;
        }
        return null;
    }

    private static boolean isBuiltinCredentialKey(String key) {
        if (key == null) return false;
        return BUILTIN_CREDENTIAL_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redactMapRecursive(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return Collections.emptyMap();
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            redacted.put(entry.getKey(), redactValue(entry.getValue()));
        }
        return redacted;
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) return redactMapRecursive((Map<String, Object>) value);
        if (value instanceof List) {
            List<Object> list = new ArrayList<>(((List<?>) value).size());
            for (Object item : (List<?>) value) list.add(redactValue(item));
            return list;
        }
        return value instanceof String ? redact((String) value) : value;
    }

    /** 把 Observation 内的 message/observedState/rejectionReason 全部过一遍,返回新 Observation。 */
    public ToolObservation redact(ToolObservation observation) {
        if (observation == null) return null;
        if (observation.getResult() == null) {
            return ToolObservation.fromParts(observation.getToolCallId(),
                    observation.getCapabilityName(), null,
                    redact(observation.getRejectionReason()),
                    observation.isCapabilityBlocked());
        }
        ToolResult original = observation.getResult();
        if (FULL_REDACT_CAPABILITIES.contains(observation.getCapabilityName())) {
            return redactMemoryObservation(observation, original);
        }
        // 有 AuditSchema 时按 schema 投影——message 用模板,
        // observedState 中 sensitiveObservedFields 列出的字段替换为占位符,
        // 其余字段仍走默认 secret mask + 截断。
        com.matrix.agent.task.capability.CapabilityDefinition capDef =
                capabilityRegistry == null ? null : capabilityRegistry.find(observation.getCapabilityName());
        if (capDef != null) {
            String template = capDef.getAuditMessageTemplate();
            java.util.Map<String, String> sensitiveObserved = capDef.getSensitiveObservedFields();
            String failureTemplate = capDef.getAuditFailureMessageTemplate();
            java.util.Set<String> observedAllowlist = capDef.getAuditObservedAllowlist();
            if (template != null || !sensitiveObserved.isEmpty()
                    || failureTemplate != null || !observedAllowlist.isEmpty()) {
                return redactWithSchema(observation, original, template, failureTemplate,
                        sensitiveObserved, observedAllowlist);
            }
        }
        ToolResult redacted = new ToolResult(original.getStatus(), original.getCapabilityName(),
                // 无 audit template 的 capability (如 knowledge.answer) 的 message
                // 也是 Provider 自由文本——可能回显用户问题 / 地址 / 联系人。一律走 redactFreeText
                // (chars + SHA-8 摘要) 而非 redact(message) (仅凭据正则 + 截断),fail-closed。
                redactFreeText(original.getMessage()), redactMap(original.getObservedState()),
                original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                redacted, null, false);
    }

    /**
     * 按 AuditSchema 投影 ToolResult。
     *
     * <p>Provider 路径不变——MockCapabilityProvider 仍返回真实 message / observedState 给 ModelSanitizer
     * (模型需要真实回读);只在 Audit 视图(Trajectory / UI / Log)替换为模板与占位符。
     *
     * <p>message 与 observedState 都走 fail-closed 路径。
     * <ul>
     *   <li>SUCCESS:message 用 {@code messageTemplate}(未配置则 redact 原文)。</li>
     *   <li>FAILURE(EXECUTION_FAILED / VERIFICATION_FAILED):message 优先用
     *       {@code failureTemplate},未配置但已声明 Audit Schema(messageTemplate 非空)
     *       时用 {@link #FAILURE_MESSAGE_FALLBACK},**绝不 fall through 到 redact 原文**
     *       ——Provider 失败文本常含目的地 / 地址 / 联系人,凭据正则识别不出。</li>
     *   <li>observedState:进入本方法即 fail-closed——sensitiveObservedFields 字段用占位符;
     *       allowlist 字段走 redactValue;**其余字段一律 {@code ***}**。
     *       Provider 新增 schema 外字段(如 navigation.poi / route.destination)默认 mask。</li>
     * </ul>
     */
    private ToolObservation redactWithSchema(ToolObservation observation, ToolResult original,
            String messageTemplate, String failureTemplate,
            java.util.Map<String, String> sensitiveObserved, java.util.Set<String> observedAllowlist) {
        // 1. message 处理 —— SUCCESS 走模板 / FAILURE fail-closed
        String auditMessage;
        boolean usedFailureTemplate = false;
        boolean usedFallback = false;
        if (original.isSuccess()) {
            auditMessage = messageTemplate != null ? messageTemplate : redact(original.getMessage());
        } else {
            if (failureTemplate != null) {
                auditMessage = failureTemplate;
                usedFailureTemplate = true;
            } else if (messageTemplate != null) {
                // capability 已声明 Audit Schema(messageTemplate 非空)→ 失败也必须 fail-closed,
                // 不允许 fall through 到 redact(message)(凭据正则识别不出业务值)。
                auditMessage = FAILURE_MESSAGE_FALLBACK;
                usedFallback = true;
            } else {
                auditMessage = redact(original.getMessage());
            }
        }

        // 2. observedState 处理 —— 进入 redactWithSchema 即 fail-closed:
        //    sensitiveObservedFields 用占位符;allowlist 字段走 redactValue;**其余一律 {@code ***}**。
        //    Provider 新增 schema 外字段(如 navigation.poi / route.destination)默认 mask。
        java.util.Map<String, Object> redactedObserved = new LinkedHashMap<>();
        int maskedUnknown = 0;
        if (original.getObservedState() != null) {
            for (java.util.Map.Entry<String, Object> entry : original.getObservedState().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                String placeholder = sensitiveObserved.get(key);
                if (placeholder != null) {
                    redactedObserved.put(key, placeholder);
                } else if (observedAllowlist.contains(key)) {
                    redactedObserved.put(key, redactValue(value));
                } else {
                    // schema 外 observedState 字段 fail-closed
                    redactedObserved.put(key, "***");
                    maskedUnknown++;
                }
            }
        }
        Log.d(TAG, "[Audit] schema-projected capability=" + observation.getCapabilityName()
                + " status=" + original.getStatus()
                + " usedFailureTemplate=" + usedFailureTemplate
                + " usedFallback=" + usedFallback
                + " maskedUnknownFields=" + maskedUnknown
                + " sensitiveFields=" + sensitiveObserved.keySet()
                + " allowlist=" + observedAllowlist);
        ToolResult redacted = new ToolResult(original.getStatus(), original.getCapabilityName(),
                auditMessage, redactedObserved, original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                redacted, null, false);
    }

    /**
     * memory.preference.* 类 observation 全脱敏:所有 value → {@code <memory>},
     * message → {@link #MEMORY_MESSAGE_PLACEHOLDER}。
     */
    private ToolObservation redactMemoryObservation(ToolObservation observation, ToolResult original) {
        Map<String, Object> redactedMap = new LinkedHashMap<>();
        int masked = 0;
        if (original.getObservedState() != null) {
            for (Map.Entry<String, Object> entry : original.getObservedState().entrySet()) {
                redactedMap.put(entry.getKey(), "<memory>");
                masked++;
            }
        }
        Log.d(TAG, "[Audit] memory capability=" + observation.getCapabilityName()
                + " maskedValues=" + masked);
        ToolResult redacted = new ToolResult(original.getStatus(), original.getCapabilityName(),
                MEMORY_MESSAGE_PLACEHOLDER, redactedMap,
                original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                redacted, null, false);
    }
}
