package com.matrix.agent.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/** Regression guard for activation/delete coordination around Host-private MNN files. */
public final class ModelGatewayRepositoryMutationLockTest {

    @Test
    public void onDeviceMutationLockSerializesActivationAndDeletion() throws Exception {
        ModelGatewayRepository repository = new ModelGatewayRepository(null, null, null);
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> firstFuture = first.submit(() -> repository.withOnDeviceMutationLock(() -> {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                return null;
            }));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            java.util.concurrent.Future<?> secondFuture = second.submit(() -> repository.withOnDeviceMutationLock(() -> {
                secondEntered.countDown();
                return null;
            }));
            assertFalse("a delete must not enter while activation owns the model files",
                    secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            firstFuture.get(2, TimeUnit.SECONDS);
            secondFuture.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            first.shutdownNow();
            second.shutdownNow();
        }
    }

    @Test
    public void modelMutationLockSerializesCloudProvisionAndBootRecovery() throws Exception {
        ModelGatewayRepository repository = new ModelGatewayRepository(null, null, null);
        ExecutorService first = Executors.newSingleThreadExecutor();
        ExecutorService second = Executors.newSingleThreadExecutor();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> firstFuture = first.submit(() -> repository.withModelMutationLock(() -> {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                return null;
            }));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            java.util.concurrent.Future<?> secondFuture = second.submit(() -> repository.withModelMutationLock(() -> {
                secondEntered.countDown();
                return null;
            }));
            assertFalse("a startup recovery must not publish while a newer provision is committing",
                    secondEntered.await(150, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            firstFuture.get(2, TimeUnit.SECONDS);
            secondFuture.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            first.shutdownNow();
            second.shutdownNow();
        }
    }
}
