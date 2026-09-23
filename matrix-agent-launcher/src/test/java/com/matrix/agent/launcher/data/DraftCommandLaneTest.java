package com.matrix.agent.launcher.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 草稿命令 lane（I4 §7.2 Launcher 侧半边）：单线程 FIFO 保证
 * saveDraft / submitTextOrAppend / discard 的全序串行——提交后到达的保存请求
 * 在 lane 上必然排在提交之后，Host tombstone 再兜底拒绝。
 */
public final class DraftCommandLaneTest {

    @Test
    public void commandsExecuteInSubmissionOrder() throws InterruptedException {
        ExecutorService singleThread = Executors.newSingleThreadExecutor();
        try {
            DraftCommandLane lane = new DraftCommandLane(null, singleThread);
            List<Integer> order = new ArrayList<>();
            List<java.util.concurrent.CountDownLatch> done = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                done.add(latch);
                int index = i;
                lane.execute("conv-1", () -> {
                    order.add(index);
                    latch.countDown();
                });
            }
            for (java.util.concurrent.CountDownLatch latch : done) {
                assertTrueAwait(latch);
            }
            assertEquals(List.of(0, 1, 2, 3, 4), order);
        } finally {
            singleThread.shutdownNow();
        }
    }

    @Test
    public void commandFailureDoesNotBreakTheLane() throws InterruptedException {
        ExecutorService singleThread = Executors.newSingleThreadExecutor();
        try {
            DraftCommandLane lane = new DraftCommandLane(null, singleThread);
            java.util.concurrent.CountDownLatch survived =
                    new java.util.concurrent.CountDownLatch(1);
            lane.execute("conv-1", () -> {
                throw new IllegalStateException("boom");
            });
            lane.execute("conv-1", survived::countDown);
            assertTrueAwait(survived);
        } finally {
            singleThread.shutdownNow();
        }
    }

    private static void assertTrueAwait(java.util.concurrent.CountDownLatch latch)
            throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("lane command did not finish in time");
        }
    }
}
