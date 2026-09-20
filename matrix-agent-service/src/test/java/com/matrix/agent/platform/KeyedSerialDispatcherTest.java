package com.matrix.agent.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Keyed FIFO 串行与跨 key 并发契约（设计文档 §4.3-5 / §11.1 keyed dispatcher 行）。 */
public final class KeyedSerialDispatcherTest {

    /** 同线程排水：确定性验证 FIFO 与拒绝语义。 */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                command.run();
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
    }

    /** 拒绝一切排水的饱和池。 */
    private static ExecutorService rejectingExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                throw new RejectedExecutionException("saturated");
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
    }

    /** 挂起不排水的池：验证 pending 上限。 */
    private static ExecutorService parkingExecutor(Deque<Runnable> parked) {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) {
                parked.add(command);
            }
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }
        };
    }

    @Test public void sameKeyTasksRunInFifoOrder() {
        List<String> order = new ArrayList<>();
        KeyedSerialDispatcher dispatcher =
                new KeyedSerialDispatcher("test", directExecutor(), 16);
        for (int i = 0; i < 5; i++) {
            int index = i;
            dispatcher.execute("conv", () -> order.add("task-" + index));
        }
        assertEquals(List.of("task-0", "task-1", "task-2", "task-3", "task-4"), order);
        assertTrue(dispatcher.isIdle());
    }

    @Test public void taskFailureDoesNotBlockSubsequentSameKeyTasks() {
        List<String> order = new ArrayList<>();
        KeyedSerialDispatcher dispatcher =
                new KeyedSerialDispatcher("test", directExecutor(), 16);
        dispatcher.execute("conv", () -> {
            throw new IllegalStateException("boom");
        });
        dispatcher.execute("conv", () -> order.add("after"));
        assertEquals(List.of("after"), order);
    }

    @Test public void boundedQueueRejectsWithoutEnqueue() {
        KeyedSerialDispatcher dispatcher =
                new KeyedSerialDispatcher("test", rejectingExecutor(), 4);
        AtomicInteger executed = new AtomicInteger();
        assertThrows(KeyedSerialDispatcher.RejectedExecutionDispatcherException.class,
                () -> dispatcher.execute("conv", executed::incrementAndGet));
        assertEquals(0, executed.get());
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test public void totalPendingCapRejectsEarly() {
        Deque<Runnable> parked = new ArrayDeque<>();
        KeyedSerialDispatcher dispatcher =
                new KeyedSerialDispatcher("test", parkingExecutor(parked), 2);
        dispatcher.execute("a", () -> { });
        dispatcher.execute("b", () -> { });
        assertThrows(KeyedSerialDispatcher.RejectedExecutionDispatcherException.class,
                () -> dispatcher.execute("c", () -> { }));
        assertEquals(2, dispatcher.pendingCount());
        assertFalse(dispatcher.isIdle());
    }

    @Test(timeout = 10_000) public void differentKeysCanRunConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            KeyedSerialDispatcher dispatcher = new KeyedSerialDispatcher("test", pool, 16);
            Object gate = new Object();
            List<String> done = new ArrayList<>();
            dispatcher.execute("a", () -> {
                synchronized (gate) {
                    try {
                        gate.wait(); // a 占住一个线程直到 b 也进入
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                done.add("a");
            });
            dispatcher.execute("b", () -> done.add("b"));
            long deadline = System.currentTimeMillis() + 5_000;
            while (!done.contains("b") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("不同 key 必须可并发（b 不应被 a 阻塞）", done.contains("b"));
            synchronized (gate) {
                gate.notifyAll();
            }
            deadline = System.currentTimeMillis() + 5_000;
            while (!done.contains("a") && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(done.contains("a"));
        } finally {
            pool.shutdownNow();
        }
    }
}
