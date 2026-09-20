package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.identity.Actor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/** 只读导出（评估 v1.0 §4.9）：内容边界、越权拒识、取消清理、文件名/slug、到期清扫。 */
public final class ConversationExporterTest {

    @Rule
    public TemporaryFolder cacheRoot = new TemporaryFolder();

    private static final String OWNER = "demo-driver";

    private FakeConversationStore store;
    private ConversationCoordinator coordinator;
    private ConversationExporter exporter;
    private File cacheDir;

    /** 直驱执行器（同步确定性）。 */
    private static final ExecutorService DIRECT =
            new ConversationCoordinatorTest.DirectPool();

    @Before
    public void setUp() throws IOException {
        store = new FakeConversationStore();
        coordinator = ConversationCoordinatorTest.buildHarness(store, successExecutor());
        exporter = new ConversationExporter(store, DIRECT);
        cacheDir = cacheRoot.newFolder("cache");
    }

    /** 导出内容：标题/条数/时间线/引用快照与状态名；不含内部 idempotency 键。 */
    @Test public void exportContainsVisibleProjectionOnly() throws IOException {
        String conv = seedConversationWithTitle("空调对话");
        ConversationCoordinator.TextAccepted first = coordinator.submitText(
                textCommand(conv, "帮我把空调调到二十四度"));
        coordinator.submitText(new ConversationCoordinator.TextCommand(conv, "再调低一点",
                "zh-CN", UUID.randomUUID().toString(), Actor.DRIVER,
                ConversationIds.agentSessionId(conv, "DRIVER", "DRIVER"), "demo-vehicle",
                null, null, first.userMessageId()));

        ConversationExporter.ExportResult result = exporter.exportMarkdownSync(
                context(), conv, OWNER, null, notCancelled());

        assertTrue(result.success());
        assertNotNull(result.file());
        String content = new String(Files.readAllBytes(result.file().toPath()),
                StandardCharsets.UTF_8);
        assertTrue(content.contains("# 空调对话"));
        assertTrue(content.contains("**用户**（已完成）"));
        assertTrue(content.contains("帮我把空调调到二十四度"));
        assertTrue(content.contains("> 引用：帮我把空调调到二十四度"));
        assertFalse("不泄漏内部幂等键", content.contains("idem"));
        assertFalse("不泄漏消息 UUID", content.contains(first.userMessageId()));
        assertTrue(result.file().getName().endsWith(".md"));
    }

    /** 越权/不存在统一 NOT_FOUND——不区分，不泄漏存在性。 */
    @Test public void exportRejectsForeignOrMissingConversation() {
        String conv = seedConversationWithTitle("我的对话");
        assertEquals("越权统一失败",
                "CONVERSATION_NOT_FOUND",
                exporter.exportMarkdownSync(context(), conv, "other-user", null,
                        notCancelled()).errorCode());
        assertEquals("不存在统一失败",
                "CONVERSATION_NOT_FOUND",
                exporter.exportMarkdownSync(context(), UUID.randomUUID().toString(),
                        OWNER, null, notCancelled()).errorCode());
    }

    /** 取消：产物文件删除，错误码 CANCELLED。 */
    @Test public void cancelledExportDeletesPartialFile() throws IOException {
        String conv = seedConversationWithTitle("取消测试");
        coordinator.submitText(textCommand(conv, "第一轮"));
        coordinator.submitText(textCommand(conv, "第二轮"));

        ConversationExporter.ExportResult result = exporter.exportMarkdownSync(
                context(), conv, OWNER, null, () -> true /* 立即取消 */);

        assertFalse(result.success());
        assertEquals("CANCELLED", result.errorCode());
        File[] leftovers = exportsDir().listFiles();
        assertNotNull(leftovers);
        assertEquals("半成品清理", 0, leftovers.length);
    }

    /** slug：非法字符折叠、上限 24、空标题回退 chat。 */
    @Test public void slugFoldsIllegalCharsAndCapsLength() {
        assertEquals("空调-调温", ConversationExporter.slugOf("空调/调温"));
        assertEquals(24, ConversationExporter.slugOf("长".repeat(40)).length());
        assertEquals("chat", ConversationExporter.slugOf("???"));
        assertEquals("chat", ConversationExporter.slugOf(""));
    }

    /** 到期清扫：过期文件删除、新文件保留。 */
    @Test public void purgeRemovesOnlyExpiredFiles() throws IOException {
        File dir = exportsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdir failed");
        }
        File stale = new File(dir, "stale.md");
        File fresh = new File(dir, "fresh.md");
        Files.write(stale.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(fresh.toPath(), "new".getBytes(StandardCharsets.UTF_8));
        //noinspection ResultOfMethodCallIgnored
        stale.setLastModified(System.currentTimeMillis()
                - ConversationExporter.RETENTION_MS - 60_000);

        ConversationExporter.purgeExpired(dir);

        assertFalse("过期删除", stale.exists());
        assertTrue("保留期内保留", fresh.exists());
    }

    // ---------------------------------------------------------------- 夹具

    private android.content.Context context() {
        // exportMarkdownSync 仅消费 getCacheDir；ContextWrapper 包 null base，
        // 其余调用经 returnDefaultValues 返回默认值
        return new android.content.ContextWrapper(null) {
            @Override public File getCacheDir() {
                return cacheDir;
            }
        };
    }

    private static BooleanSupplier notCancelled() {
        return () -> false;
    }

    private String seedConversationWithTitle(String title) {
        String conv = UUID.randomUUID().toString();
        store.seedConversation(conv, OWNER);
        store.renameConversation(conv, title);
        return conv;
    }

    private ConversationCoordinator.TextCommand textCommand(String conv, String text) {
        return new ConversationCoordinator.TextCommand(conv, text, "zh-CN",
                UUID.randomUUID().toString(), Actor.DRIVER,
                ConversationIds.agentSessionId(conv, "DRIVER", "DRIVER"), "demo-vehicle");
    }

    private File exportsDir() {
        return new File(cacheDir, "exports");
    }

    /** 成功执行桩：终态 COMPLETED + 助手固定文本（导出内容确定性）。 */
    static ConversationCoordinator.TaskExecutor successExecutor() {
        return (task, token) -> {
            com.matrix.agent.task.Trajectory trajectory =
                    new com.matrix.agent.task.Trajectory();
            trajectory.finish(com.matrix.agent.task.StopReason.NO_TOOL_CALL, 1L, 0);
            return new com.matrix.agent.task.AgentOutcome(UUID.randomUUID().toString(),
                    com.matrix.agent.task.TaskState.SUCCEEDED,
                    com.matrix.agent.task.StopReason.NO_TOOL_CALL, trajectory, 1L,
                    java.util.List.of(), "已按你的要求完成。");
        };
    }

    /** 消除未用告警的占位引用（Info 常量来自冻结 DTO）。 */
    @SuppressWarnings("unused")
    private static void constantsAnchor() {
        List.of(ConversationInfo.TITLE_ORIGIN_USER, ConversationMessage.INPUT_STEER);
    }
}
