package net.shiroha233.roadweaver.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolManagerTest {
    private String previous;
    @BeforeEach void start() {
        previous = System.getProperty("roadweaver.workerThreads");
        System.setProperty("roadweaver.workerThreads", "1");
        ThreadPoolManager.onServerStarted(null);
    }
    @AfterEach void stop() {
        ThreadPoolManager.onServerStopping();
        if (previous == null) System.clearProperty("roadweaver.workerThreads");
        else System.setProperty("roadweaver.workerThreads", previous);
    }

    @Test void fourCoresReserveCapacityForMinecraftAndAllRolesShareBudget() throws Exception {
        assertEquals(1, ThreadPoolManager.defaultWorkerThreads(4));
        assertEquals(1, ThreadPoolManager.defaultWorkerThreads(1));
        assertEquals(2, ThreadPoolManager.defaultWorkerThreads(64));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (ThreadPoolManager.TaskRole role : ThreadPoolManager.TaskRole.values()) {
            tasks.add(ThreadPoolManager.runAsync(role, () -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try { assertTrue(Thread.currentThread().getName().startsWith("RW-Shared-")); }
                finally { active.decrementAndGet(); }
            }));
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals(1, maximum.get());
    }

    @Test void nestedWorkCannotDeadlockASingleWorker() throws Exception {
        CompletableFuture<Integer> outer = ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.PLANNING, () -> {
            try {
                return ThreadPoolManager.supplyAsync(ThreadPoolManager.TaskRole.COARSE, () -> 42).get(2, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new CompletionException(failure); }
        });
        assertEquals(42, outer.get(5, TimeUnit.SECONDS));
    }

    @Test void fullQueueRejectsWithoutRunningWorkOnSubmittingThread() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolManager.submit(ThreadPoolManager.TaskRole.PLANNING, () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        AtomicInteger executed = new AtomicInteger();
        try {
            for (int i = 0; i < 256; i++) ThreadPoolManager.execute(ThreadPoolManager.TaskRole.GENERATION, executed::incrementAndGet);
            assertEquals(256, ThreadPoolManager.sharedBacklog());
            assertThrows(RejectedExecutionException.class,
                    () -> ThreadPoolManager.execute(ThreadPoolManager.TaskRole.MAP, executed::incrementAndGet));
            assertEquals(0, executed.get(), "Saturation must never invoke caller-runs");
        } finally { release.countDown(); }
    }

    @Test void shutdownCompletesQueuedFuturesAndCannotRecreateWorkers() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.PLANNING, () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> queued = ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.GENERATION, () -> fail("Queued task ran after shutdown"));
        ThreadPoolManager.onServerStopping();
        assertTrue(queued.isDone());
        assertTrue(queued.isCancelled());
        assertTrue(ThreadPoolManager.computeExecutor().isShutdown());
        assertThrows(RejectedExecutionException.class, () -> ThreadPoolManager.execute(ThreadPoolManager.TaskRole.MAP, () -> {}));
        release.countDown();
    }

    @Test void shutdownWaitsForPhysicalWorkerExitEvenWhenInterruptIsIgnored() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch permitExit = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean exited = new java.util.concurrent.atomic.AtomicBoolean();
        ThreadPoolManager.runAsync(ThreadPoolManager.TaskRole.GENERATION, () -> {
            entered.countDown();
            try {
                while (permitExit.getCount() != 0) {
                    try { permitExit.await(); } catch (InterruptedException ignored) { }
                }
                assertFalse(ThreadPoolManager.isCurrentWorkerSession(), "A stale worker must lose mutation permission");
            } finally { exited.set(true); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread release = new Thread(() -> {
            while (!ThreadPoolManager.isStopping()) Thread.onSpinWait();
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            permitExit.countDown();
        });
        release.start();
        ThreadPoolManager.onServerStopping();
        release.join(5000);
        assertTrue(exited.get(), "Cache teardown must follow actual worker termination");
    }
}
