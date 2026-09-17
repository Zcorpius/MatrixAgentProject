package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 模型安装/迁移/回滚的统一锁协调器(P1-5b / P2-1)。
 *
 * <p>进程内 per-langDir {@link ReentrantLock} + 跨进程 {@link FileLock}({@code .lock_<langDir name>})。
 * 所有改 active/previous 指针的操作(promote / migrateLegacy / rollbackActive)都经本类,
 * 消除指针竞争。
 *
 * <p><b>withTryLock</b>(install/migrate):{@link ReentrantLock#tryLock()} 立即返回——同进程第二个调用者
 * 立即收到 {@link LockBusyException}(不卡 monitor,可被 shutdownNow 取消);跨进程 {@link FileLock#tryLock()}
 * null 也抛 LockBusy。
 *
 * <p><b>withLock</b>(rollback):{@link ReentrantLock#lockInterruptibly()} 可中断等(shutdownNow 能取消);
 * 跨进程 {@link FileChannel#lock()} 阻塞等持锁者(回滚必须执行,不能 LockBusy)。
 *
 * <p>锁 key = {@code langDir.getAbsolutePath()}({@code spec.targetDir == langDir})。
 */
public final class ModelInstallLock {
    private static final ConcurrentHashMap<String, ReentrantLock> INTRA = new ConcurrentHashMap<>();

    private ModelInstallLock() {}

    private static ReentrantLock intraLock(File langDir) {
        return INTRA.computeIfAbsent(langDir.getAbsolutePath(), k -> new ReentrantLock());
    }

    /** 进程内 tryLock + 跨进程 tryLock;任一忙 → {@link LockBusyException}(立即返回,不等)。用于 install/migrate。 */
    public static void withTryLock(File langDir, ThrowingRunnable run) throws IOException {
        ReentrantLock rl = intraLock(langDir);
        if (!rl.tryLock()) throw new LockBusyException(langDir.getName()); // 同进程忙,立即返回
        try {
            fileLock(langDir, run, true);
        } finally {
            rl.unlock();
        }
    }

    /** 进程内 lockInterruptibly(可中断)+ 跨进程阻塞 lock(等持锁者);用于 rollback(不能 LockBusy)。 */
    public static void withLock(File langDir, ThrowingRunnable run) throws IOException {
        ReentrantLock rl = intraLock(langDir);
        try {
            rl.lockInterruptibly(); // 可中断:shutdownNow 能取消等待
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LOCK_INTERRUPTED: " + langDir.getName());
        }
        try {
            fileLock(langDir, run, false);
        } finally {
            rl.unlock();
        }
    }

    private static void fileLock(File langDir, ThrowingRunnable run, boolean tryOnly) throws IOException {
        File lockFile = new File(langDir.getParentFile(), ".lock_" + langDir.getName());
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
             FileChannel ch = raf.getChannel()) {
            FileLock fl = tryOnly ? ch.tryLock() : ch.lock();
            if (fl == null) throw new LockBusyException(langDir.getName()); // 仅 tryOnly 可能 null
            try {
                run.run();
            } finally {
                try { fl.release(); } catch (Exception ignored) { }
            }
        } catch (OverlappingFileLockException e) {
            // 同进程 ReentrantLock 已互斥,兜底
            throw new LockBusyException(langDir.getName());
        }
    }

    /** 锁竞争(另一实例/进程正在安装同 langDir)。不删 tmp,调用方退避重试/跳过。 */
    public static final class LockBusyException extends IOException {
        public LockBusyException(String name) {
            super("LOCK_BUSY: " + name);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws IOException;
    }
}
