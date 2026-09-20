package com.matrix.agent.conversation;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.api.conversation.ConversationMessage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 会话只读导出（评估 v1.0 §4.9 / 阶段 4）。
 *
 * <p><b>边界</b>：由 Host 在自有进程生成——Launcher 永远只拿到成品文件的受授予 URI，
 * 不接触消息原始查询。导出内容是“可见最终投影”：会话标题、消息文本/角色/状态/
 * 时间 + 引用与谱系的可见快照；不包含原始 prompt、模型草稿、能力审计敏感字段、
 * 未脱敏 readback 或内部 idempotency 键。</p>
 *
 * <p><b>产物纪律</b>：写入 app 私有 {@code cacheDir/exports/}（SQLCipher 进程私有区），
 * 到期自动清理（默认 24h，每次导出顺带清扫）；文件名含时间戳与会话标题 slug，
 * 不泄漏内部 UUID。分享经 FileProvider 由用户显式动作触发，导出本身不外发。</p>
 *
 * <p><b>取消/进度</b>：{@code cancelled} 协作检查在每条消息落盘间隙执行；进度按
 * 已写消息数回调（UI 线程编组由调用方负责）。</p>
 */
public final class ConversationExporter {

    private static final String TAG = "MatrixAgent";
    private static final String EXPORT_DIR = "exports";
    /** 导出文件保留期：到期在下一次任意导出时清扫（车机存储预算纪律）。 */
    static final long RETENTION_MS = 24L * 60 * 60 * 1000;

    private final ConversationStore store;
    private final ExecutorService lane;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public ConversationExporter(ConversationStore store, ExecutorService lane) {
        this.store = Objects.requireNonNull(store, "store");
        this.lane = Objects.requireNonNull(lane, "lane");
    }

    /** 进度回调：written 为已落盘消息数，total 为本会话消息总数。 */
    public interface ProgressListener {
        void onProgress(int written, int total);
    }

    /** 导出结果：成功携带产物文件；失败携带稳定原因码（不泄漏路径细节）。 */
    public record ExportResult(boolean success, File file, String errorCode) {
        static ExportResult ok(File file) {
            return new ExportResult(true, file, null);
        }

        static ExportResult fail(String code) {
            return new ExportResult(false, null, code);
        }
    }

    /**
     * 异步导出一个会话为 Markdown（人读格式，Operit MarkdownExporter 的
     * “标题 + 元信息 + 时间线”结构，去掉其变体/角色卡等 Matrix 不存在的字段）。
     */
    public void exportMarkdown(Context context, String conversationId, String ownerUserId,
            ProgressListener progress, BooleanSupplier cancelled,
            java.util.function.Consumer<ExportResult> completion) {
        try {
            lane.execute(() -> completion.accept(
                    exportMarkdownSync(context, conversationId, ownerUserId, progress,
                            cancelled)));
        } catch (RejectedExecutionException unavailable) {
            completion.accept(ExportResult.fail("EXECUTOR_UNAVAILABLE"));
        }
    }

    /** 同步导出（lane 线程体；JVM 测试直调）。 */
    public ExportResult exportMarkdownSync(Context context, String conversationId,
            String ownerUserId, ProgressListener progress, BooleanSupplier cancelled) {
        ConversationStore.ConversationRow conversation = store.findConversation(conversationId);
        if (conversation == null || !conversation.ownerUserId().equals(ownerUserId)) {
            return ExportResult.fail("CONVERSATION_NOT_FOUND"); // 不区分不存在/越权
        }
        List<ConversationStore.MessageRow> messages =
                store.pageMessages(conversationId, -1L, Integer.MAX_VALUE)
                        .messagesAscending();
        File dir = new File(context.getCacheDir(), EXPORT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return ExportResult.fail("CREATE_DIR_FAILED");
        }
        purgeExpired(dir);

        File target = new File(dir, fileNameFor(conversation));
        long startedAt = System.currentTimeMillis();
        int written = 0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8))) {
            writer.write("# " + displayTitle(conversation));
            writer.newLine();
            writer.newLine();
            writer.write("- 导出时间：" + formatDate(startedAt));
            writer.newLine();
            writer.write("- 消息条数：" + messages.size());
            writer.newLine();
            String lineage = lineageSummary(conversationId);
            if (lineage != null) {
                writer.write("- 来源：" + lineage);
                writer.newLine();
            }
            writer.newLine();
            for (ConversationStore.MessageRow row : messages) {
                if (cancelled.getAsBoolean()) {
                    writer.close();
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    return ExportResult.fail("CANCELLED");
                }
                writeMessage(writer, row);
                written++;
                if (progress != null && (written % 25 == 0 || written == messages.size())) {
                    progress.onProgress(written, messages.size());
                }
            }
        } catch (IOException ioFailure) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            Log.w(TAG, "[Conversation] 导出失败 conv=" + conversationId + " reason="
                    + ioFailure.getClass().getSimpleName());
            return ExportResult.fail("IO_FAILURE");
        }
        Log.i(TAG, "[Conversation] 导出完成 conv=" + conversationId + " messages=" + written
                + " file=" + target.getName());
        return ExportResult.ok(target);
    }

    private void writeMessage(BufferedWriter writer, ConversationStore.MessageRow row)
            throws IOException {
        String speaker = row.roleWire() == ConversationMessage.ROLE_USER ? "用户"
                : row.roleWire() == ConversationMessage.ROLE_ASSISTANT ? "助手" : "系统";
        writer.write("**" + speaker + "**（" + statusName(row.statusWire()) + "） "
                + formatDate(row.createdAtMs()));
        writer.newLine();
        ConversationStore.QuoteRow quote = store.findQuoteByQuotingMessage(row.messageId());
        if (quote != null) {
            writer.write("> 引用：" + quote.snapshot());
            writer.newLine();
        }
        writer.write(row.text());
        writer.newLine();
        writer.newLine();
    }

    private String lineageSummary(String conversationId) {
        ConversationStore.LineageRow lineage = store.findLineage(conversationId);
        if (lineage == null) {
            return null;
        }
        if (lineage.parentConversationId() == null) {
            return "来源已清除";
        }
        return lineage.parentTitleAtFork() == null || lineage.parentTitleAtFork().isBlank()
                ? "来自另一段对话" : "来自“" + lineage.parentTitleAtFork() + "”";
    }

    private static String displayTitle(ConversationStore.ConversationRow conversation) {
        return conversation.title() == null || conversation.title().isBlank()
                ? "未命名对话" : conversation.title();
    }

    private static String statusName(int statusWire) {
        switch (statusWire) {
            case 0: return "已接收";
            case 1: return "执行中";
            case 2: return "已完成";
            case 3: return "未完成";
            case 4: return "已取消";
            case 5: return "结果未知";
            case 6: return "未执行";
            default: return "未知状态";
        }
    }

    private static String formatDate(long epochMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(epochMillis));
    }

    /** 文件名：导出时间 + 标题 slug；不泄漏内部 UUID。 */
    static String fileNameFor(ConversationStore.ConversationRow conversation) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)
                .format(new Date());
        String slug = slugOf(displayTitle(conversation));
        return "matrix-" + stamp + "-" + slug + ".md";
    }

    /** 标题 slug：仅保留中日韩文字/字母/数字，其余折叠为连字符，上限 24 字符。 */
    static String slugOf(String title) {
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < title.length() && slug.length() < 24; i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 0x10000
                    && !Character.isWhitespace(c)) {
                slug.append(c);
            } else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        int length = slug.length();
        if (length == 0 || slug.charAt(length - 1) == '-') {
            return length <= 1 ? "chat" : slug.substring(0, length - 1);
        }
        return slug.toString();
    }

    /** 到期清扫：超过保留期的导出文件删除（每次导出顺带执行）。 */
    static void purgeExpired(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long deadline = System.currentTimeMillis() - RETENTION_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < deadline) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }
}
