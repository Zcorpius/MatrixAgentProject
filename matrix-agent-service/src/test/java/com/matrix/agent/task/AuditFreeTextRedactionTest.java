package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.tool.ToolCall;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验证审计侧 assistant content 自由文本走 fail-closed 路径
 * ({@code [redacted:chars=N,sha=xxxxxxxx]}),不再凭据正则 + 长度截断——
 * 模型把 PII (地址 / 联系人 / 电话) 回显进 assistant content 时,凭据正则识别不出。
 *
 * <p>原 {@link AuditRedactor#redact(String)} 路径 (Tool arguments / ToolResult message)
 * 不动,本测试只覆盖新 {@link AuditRedactor#redactFreeText(String)}。
 * AgentEngine 装配点 ({@code auditRedactAssistant} L585) 是单行调用,改动语义由本测试 + 既有
 * AgentEngineAuditTest 双重覆盖 (后者断言 trajectory 不含原文 PII)。
 */
public final class AuditFreeTextRedactionTest {
    private static final Pattern REDACTED_FORMAT = Pattern.compile(
            "^\\[redacted:chars=(\\d+),sha=([0-9a-f]{8})\\]$");

    /**
     * 测试 SHA-1 兼容路径必须**显式**注入 {@link Sha1AuditDigest}
     * ——AuditRedactor 默认已改为 {@link UnavailableAuditDigest}(fail-closed),
     * 不再退回 SHA-1。本 helper 把"显式 opt-in SHA-1"集中到一处,避免每个 case 重复样板。
     */
    private static AuditRedactor newSha1Redactor(int maxChars) {
        AuditRedactor redactor = new AuditRedactor(maxChars);
        redactor.setDigest(new Sha1AuditDigest());
        return redactor;
    }

    /** 同上,带 CapabilityRegistry 重载——redactArguments 按 schema 投影的 case 用。 */
    private static AuditRedactor newSha1Redactor(int maxChars, CapabilityRegistry registry) {
        AuditRedactor redactor = new AuditRedactor(maxChars, registry);
        redactor.setDigest(new Sha1AuditDigest());
        return redactor;
    }

    @Test
    public void redactFreeTextReturnsCharsAndSha() {
        AuditRedactor redactor = newSha1Redactor(4096);
        String input = "把主驾温度调到 24 度";
        String result = redactor.redactFreeText(input);

        Matcher m = REDACTED_FORMAT.matcher(result);
        assertTrue("redactFreeText 输出必须匹配 [redacted:chars=N,sha=xxxxxxxx], 实际=" + result,
                m.matches());
        assertEquals("chars 必须等于原文长度",
                String.valueOf(input.length()), m.group(1));
        assertEquals("sha 必须是 8 位小写十六进制",
                8, m.group(2).length());
    }

    @Test
    public void redactFreeTextNullEmpty() {
        AuditRedactor redactor = newSha1Redactor(4096);
        assertEquals("null 输入应返回空串", "", redactor.redactFreeText(null));
        String emptyResult = redactor.redactFreeText("");
        Matcher m = REDACTED_FORMAT.matcher(emptyResult);
        assertTrue("空串输入也应返回标准格式", m.matches());
        assertEquals("0", m.group(1));
    }

    @Test
    public void redactFreeTextChineseStable() {
        AuditRedactor redactor = newSha1Redactor(4096);
        String input = "导航到北京市朝阳区幸福路 88 号";
        String first = redactor.redactFreeText(input);
        String second = redactor.redactFreeText(input);
        assertEquals("相同输入必须产生相同摘要 (稳定性)", first, second);

        Matcher m = REDACTED_FORMAT.matcher(first);
        assertTrue(m.matches());
        // 中文输入长度 (含空格) 是稳定诊断值
        assertEquals(String.valueOf(input.length()), m.group(1));
    }

    @Test
    public void redactFreeTextDifferentInputsDiffer() {
        AuditRedactor redactor = newSha1Redactor(4096);
        String a = "把主驾温度调到 24 度";
        String b = "把主驾温度调到 25 度";
        assertFalse("不同输入必须产生不同摘要",
                redactor.redactFreeText(a).equals(redactor.redactFreeText(b)));
    }

    @Test
    public void redactFreeTextDoesNotLeakPii() {
        AuditRedactor redactor = newSha1Redactor(4096);
        // 模型可能把 PII 回显进 assistant content
        String sensitiveContent = "好的,已记录您的家庭地址:北京市朝阳区幸福路 88 号,"
                + "联系电话 13800001234,联系人 张三";
        String result = redactor.redactFreeText(sensitiveContent);
        assertFalse("不应泄露地址 '幸福路'", result.contains("幸福路"));
        assertFalse("不应泄露电话 '13800001234'", result.contains("13800001234"));
        assertFalse("不应泄露姓名 '张三'", result.contains("张三"));
        assertFalse("不应泄露 '朝阳区'", result.contains("朝阳区"));
    }

    @Test
    public void redactFreeTextPreservesCharCountForLongInput() {
        AuditRedactor redactor = newSha1Redactor(4096);
        // 长输入 (max_tokens 截断 / 异常长输入场景) chars 仍是诊断信号
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("a");
        String input = sb.toString();
        Matcher m = REDACTED_FORMAT.matcher(redactor.redactFreeText(input));
        assertTrue(m.matches());
        assertEquals("5000", m.group(1));
    }

    /**
     * 旧 {@link AuditRedactor#redact(String)} 路径不动——验证 redact 与 redactFreeText
     * 行为互不影响:前者继续保留原文 (除凭据替换 + 截断),后者一律摘要化。
     * 这条回归断言保护历史测试不退绿。
     */
    @Test
    public void redactAndRedactFreeTextAreIndependent() {
        AuditRedactor redactor = newSha1Redactor(4096, CapabilityRegistry.createDemoRegistry());
        String input = "本机 API Key 是 sk-ant-test1234567890abcdefg";
        String redactResult = redactor.redact(input);
        String freeTextResult = redactor.redactFreeText(input);

        // redact 路径:凭据被替换为 ***,但原文部分 (本机 API Key 是) 保留
        assertTrue("redact 应替换凭据", redactResult.contains("***"));
        assertFalse("redact 不应保留原始 key", redactResult.contains("sk-ant-test1234567890abcdefg"));

        // redactFreeText 路径:整体摘要,既不保留原文也不保留凭据
        Matcher m = REDACTED_FORMAT.matcher(freeTextResult);
        assertTrue(m.matches());
        assertFalse("redactFreeText 不应保留任何原文", freeTextResult.contains("API"));
        assertFalse("redactFreeText 不应保留凭据原文", freeTextResult.contains("sk-ant"));
    }

    // ---- ToolObservation 无 audit template 路径 fail-closed ----

    /**
     * 未配 audit template 的 capability 的 ToolResult message 也是 Provider
     * 自由文本——必须走 redactFreeText,而非旧 redact(message) (仅凭据正则 + 截断)。
     *
     * <p>构造一个不在 registry 的 fake capability (避免被 demo registry 的 audit template
     * 覆盖),验证 message 不含原 PII。
     */
    @Test
    public void redactToolObservationWithoutTemplateFreeTextsMessage() {
        AuditRedactor redactor = newSha1Redactor(4096, CapabilityRegistry.createDemoRegistry());
        ToolCall call = new ToolCall("fake.unregistered.capability", new HashMap<>());
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "fake.unregistered.capability",
                "您问的地址是北京市朝阳区幸福路 88 号,联系电话 13800001234",
                Collections.<String, Object>emptyMap(), true, 100L);
        ToolObservation observation = ToolObservation.fromParts(call.getStepId(),
                "fake.unregistered.capability", result, null, false);

        ToolObservation redacted = redactor.redact(observation);
        String auditMessage = redacted.getResult().getMessage();

        Matcher m = REDACTED_FORMAT.matcher(auditMessage);
        assertTrue("无 audit template 的 capability message 应走 redactFreeText 路径, 实际="
                + auditMessage, m.matches());
        assertFalse("不应泄露地址 '幸福路'", auditMessage.contains("幸福路"));
        assertFalse("不应泄露电话 '13800001234'", auditMessage.contains("13800001234"));
    }

    /**
     * knowledge.answer 已配 auditMessageTemplate,Provider message 应替换为
     * 模板占位符 (而非原文回显用户问题 / 答案中的 PII)。
     */
    @Test
    public void knowledgeAnswerObservationUsesAuditTemplate() {
        AuditRedactor redactor = new AuditRedactor(4096, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new HashMap<>();
        args.put("question", "请告诉我张三的家庭住址和电话");
        ToolCall call = new ToolCall("knowledge.answer", args);
        // 模型回显用户问题 + 答案含 PII
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS, "knowledge.answer",
                "您问的是请告诉我张三的家庭住址和电话。张三住北京市朝阳区幸福路 88 号,电话 13800001234",
                Collections.<String, Object>emptyMap(), true, 50L);
        ToolObservation observation = ToolObservation.fromParts(call.getStepId(),
                "knowledge.answer", result, null, false);

        ToolObservation redacted = redactor.redact(observation);
        String auditMessage = redacted.getResult().getMessage();
        // 配了 audit template 的 capability message 走模板替换,不该泄露原文
        assertFalse("knowledge.answer audit message 不应泄露 '幸福路'", auditMessage.contains("幸福路"));
        assertFalse("knowledge.answer audit message 不应泄露 '13800001234'", auditMessage.contains("13800001234"));
        assertFalse("knowledge.answer audit message 不应泄露用户问题原文 '张三'", auditMessage.contains("张三"));
        assertTrue("knowledge.answer audit message 应含模板标记 'redacted'",
                auditMessage.contains("redacted"));
    }

    /**
     * knowledge.answer 失败状态走 failureTemplate —— Provider 异常 message
     * 常含敏感调试信息 (如内网 URL / 错误回显用户输入),必须用模板替换。
     */
    @Test
    public void knowledgeAnswerFailureUsesFailureTemplate() {
        AuditRedactor redactor = new AuditRedactor(4096, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new HashMap<>();
        args.put("question", "今天几号");
        ToolCall call = new ToolCall("knowledge.answer", args);
        ToolResult result = new ToolResult(ToolResult.Status.EXECUTION_FAILED, "knowledge.answer",
                "Knowledge service http://internal.llm.local/ask?q=今天几号 returned 500: timeout",
                Collections.<String, Object>emptyMap(), false, 5_000L);
        ToolObservation observation = ToolObservation.fromParts(call.getStepId(),
                "knowledge.answer", result, null, false);

        ToolObservation redacted = redactor.redact(observation);
        String auditMessage = redacted.getResult().getMessage();
        assertFalse("failure message 不应泄露内网 URL 'internal.llm.local'",
                auditMessage.contains("internal.llm.local"));
        assertFalse("failure message 不应泄露用户问题 '今天几号'",
                auditMessage.contains("今天几号"));
        assertTrue("failure message 应含模板标记 'redacted'",
                auditMessage.contains("redacted"));
    }

    // ---- HMAC-SHA-256 digest 注入路径 ----

    /**
     * 注入 HMAC digest 后,redactFreeText 输出格式应从
     * {@code [redacted:chars=N,sha=8hex]} 切换到 {@code [redacted:chars=N,hmac=16hex]}。
     *
     * <p>通过 {@link AuditRedactor#setDigest(AuditDigest)} 注入,生产路径由 AppContainer
     * 装配 {@code KeystoreHmacAuditDigest};此处用 in-memory 测试 stub 验证切换。
     */
    @Test
    public void redactFreeTextSwitchesToHmacAfterSetDigest() {
        AuditRedactor redactor = new AuditRedactor(4096);
        Pattern hmacFormat = Pattern.compile(
                "^\\[redacted:chars=(\\d+),hmac=([0-9a-f]{16})\\]$");
        redactor.setDigest(new FixedHmacDigestStub());
        String input = "把主驾温度调到 24 度";
        String result = redactor.redactFreeText(input);

        Matcher m = hmacFormat.matcher(result);
        assertTrue("注入 HMAC digest 后输出必须匹配 [redacted:chars=N,hmac=16hex], 实际=" + result,
                m.matches());
        assertEquals("chars 必须等于原文长度",
                String.valueOf(input.length()), m.group(1));
        assertEquals("hmac 必须是 16 位 hex", 16, m.group(2).length());
    }

    // ---- 默认 fail-closed + SHA-1 opt-in ----

    /**
     * 不调 setDigest 时默认走 {@link UnavailableAuditDigest}
     * ——fail-closed,任何输入产出相同摘要 ({@code digest=unavailable}),
     * 不暴露"同输入同输出"的稳定性信号,杜绝 SHA-1 8 位对低熵文本的枚举反推。
     *
     * <p>这条断言保护"未来遗漏 HMAC 注入"场景:即使 AppContainer 没调 setAuditDigest
     * (JVM 测试 fake / 新装配路径遗漏),也不会静默降低隐私级别。
     */
    @Test
    public void defaultDigestIsFailClosedUnavailable() {
        AuditRedactor redactor = new AuditRedactor(4096);
        // 不调 setDigest → 默认 UnavailableAuditDigest
        String result = redactor.redactFreeText("把主驾温度调到 24 度");
        assertEquals("默认应输出固定 unavailable 摘要",
                "[redacted:chars=12,digest=unavailable]", result);
    }

    /** setDigest(null) 同样走 fail-closed,不退回旧版 SHA-1。 */
    @Test
    public void setDigestNullIsFailClosedUnavailable() {
        AuditRedactor redactor = new AuditRedactor(4096);
        redactor.setDigest(null);
        String result = redactor.redactFreeText("hello");
        assertEquals("setDigest(null) 应 fail-closed",
                "[redacted:chars=5,digest=unavailable]", result);
    }

    /**
     * SHA-1 不再是默认值,但显式 opt-in 仍兼容旧版契约。
     * 这条断言保护历史的 6 条 sha 路径测试不退绿——它们改用 newSha1Redactor
     * helper 显式注入后,行为与旧版默认路径一致。
     */
    @Test
    public void sha1DigestIsOptInViaSetDigest() {
        AuditRedactor redactor = new AuditRedactor(4096);
        redactor.setDigest(new Sha1AuditDigest());
        String result = redactor.redactFreeText("把主驾温度调到 24 度");
        Matcher m = REDACTED_FORMAT.matcher(result);
        assertTrue("显式注入 Sha1AuditDigest 后应走 SHA-1 路径 (V0.5.0 兼容)", m.matches());
        assertEquals("8", String.valueOf(m.group(2).length()));
    }

    /**
     * 测试用 HMAC digest stub——固定 key + JRE 内置 javax.crypto.Mac。
     * 与生产 {@code KeystoreHmacAuditDigest} 不同源,但摘要行为一致 (HMAC-SHA-256 + 截断 16 hex)。
     */
    private static final class FixedHmacDigestStub implements AuditDigest {
        private final javax.crypto.SecretKey key;

        FixedHmacDigestStub() {
            this.key = new javax.crypto.spec.SecretKeySpec(
                    "fixed-test-key-for-hmac-stub!!".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256");
        }

        @Override
        public String digest(String input) {
            if (input == null) return "";
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(key);
                byte[] out = mac.doFinal(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : out) {
                    sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
                    if (sb.length() >= 16) break;
                }
                return sb.substring(0, Math.min(16, sb.length()));
            } catch (Exception e) {
                return "no-hmac";
            }
        }

        @Override
        public String prefix() {
            return "hmac";
        }
    }
}
