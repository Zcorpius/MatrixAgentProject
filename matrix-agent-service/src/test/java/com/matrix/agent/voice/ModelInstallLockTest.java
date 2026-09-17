package com.matrix.agent.voice;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * {@link ModelInstallLock} 回归测试(P2-3)。
 *
 * <p>守护:同进程第二个 withTryLock 在 holder 持锁期间必须得到 {@link ModelInstallLock.LockBusyException}
 * (真 try,不等 monitor)。用 CountDownLatch 上界(2s)判定"holder 未释放前 contender 已完成"——
 * 不对实际耗时设硬阈值(P3-3:CI 调度抖动下 200ms 阈值会偶发失败)。
 */
public final class ModelInstallLockTest {

    @Test
    public void withTryLock_secondIntraProc_busyWhileHolderHolding() throws Exception {
        File root = Files.createTempDirectory("lock-test").toFile();
        File langDir = new File(root, "en");
        assertTrue(langDir.mkdirs());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch contenderDone = new CountDownLatch(1);
        AtomicReference<ModelInstallLock.LockBusyException> second = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try {
                ModelInstallLock.withTryLock(langDir, () -> {
                    holding.countDown();
                    try { release.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                });
            } catch (Exception ignored) { }
        });
        holder.setDaemon(true);
        holder.start();
        assertTrue("holder 应持锁", holding.await(2_000, TimeUnit.MILLISECONDS));
        AtomicReference<AssertionError> contenderError = new AtomicReference<>();
        Thread contender = new Thread(() -> {
            try {
                ModelInstallLock.withTryLock(langDir, () -> { });
                contenderError.set(new AssertionError("同进程第二个 withTryLock 应 LockBusy"));
            } catch (ModelInstallLock.LockBusyException e) {
                second.set(e); // 期望路径:holder 未释放,contender 立即得到 LockBusy
            } catch (Exception e) {
                contenderError.set(new AssertionError("非预期异常: " + e));
            } finally {
                contenderDone.countDown();
            }
        });
        contender.setDaemon(true);
        contender.start();
        // 上界 2s:contender 必须在 holder 释放前完成并得到 LockBusy(真 try,不卡 monitor)
        assertTrue("contender 应在 holder 释放前完成(真 try)",
                contenderDone.await(2_000, TimeUnit.MILLISECONDS));
        assertTrue("contender 不应有非预期错误: " + contenderError.get(),
                contenderError.get() == null);
        assertNotNull("应抛 LockBusyException", second.get());
        assertTrue("holder 仍持锁(release 未发)", release.getCount() == 1);
        release.countDown(); // 释放 holder
        holder.join(1_000);
    }

    @Test
    public void withTryLock_afterRelease_succeeds() throws Exception {
        File root = Files.createTempDirectory("lock-test2").toFile();
        File langDir = new File(root, "cn");
        assertTrue(langDir.mkdirs());
        ModelInstallLock.withTryLock(langDir, () -> { }); // 获取 + finally 释放
        ModelInstallLock.withTryLock(langDir, () -> { }); // 释放后应再次成功(不 LockBusy)
    }
}
