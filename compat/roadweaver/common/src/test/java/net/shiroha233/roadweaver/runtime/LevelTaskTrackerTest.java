package net.shiroha233.roadweaver.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class LevelTaskTrackerTest {
    @AfterEach
    void cleanup() { LevelTaskTracker.shutdown(); }

    @Test
    void holdsWorldUntilFinalCallbackCompletesAndDoesNotBlockOtherWorlds() {
        Object first = new Object(), second = new Object();
        CompletableFuture<Void> worker = new CompletableFuture<>();
        CompletableFuture<Void> callback = new CompletableFuture<>();
        CompletableFuture<Void> tracked = LevelTaskTracker.track(first,
                () -> worker.thenCompose(ignored -> callback));

        worker.complete(null);
        assertTrue(LevelTaskTracker.hasPendingWork(first));
        assertFalse(LevelTaskTracker.hasPendingWork(second));
        assertThrows(IllegalStateException.class, () -> LevelTaskTracker.clear(first));

        callback.complete(null);
        tracked.join();
        assertFalse(LevelTaskTracker.hasPendingWork(first));
        LevelTaskTracker.clear(first);
    }

    @Test
    void cancellingCallerFutureDoesNotReleaseRunningWork() {
        Object level = new Object();
        CompletableFuture<Void> worker = new CompletableFuture<>();
        CompletableFuture<Void> response = LevelTaskTracker.track(level, () -> worker);
        response.cancel(false);
        assertTrue(LevelTaskTracker.hasPendingWork(level));
        worker.complete(null);
        assertFalse(LevelTaskTracker.hasPendingWork(level));
    }

    @Test
    void failureAndSubmissionRejectionReleaseCounters() {
        Object level = new Object();
        CompletableFuture<Void> worker = new CompletableFuture<>();
        CompletableFuture<Void> response = LevelTaskTracker.track(level, () -> worker);
        worker.completeExceptionally(new IllegalStateException("failed"));
        assertTrue(response.isCompletedExceptionally());
        assertFalse(LevelTaskTracker.hasPendingWork(level));

        CompletableFuture<Void> rejected = LevelTaskTracker.track(level, () -> {
            throw new RejectedExecutionException("queue full");
        });
        assertTrue(rejected.isCompletedExceptionally());
        assertFalse(LevelTaskTracker.hasPendingWork(level));
    }
}
