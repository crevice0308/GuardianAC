// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedStorageExecutorTest {
    @Test void saturationFailsExplicitlyWithoutExecutingOnCaller() throws Exception {
        CountDownLatch started = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        try (BoundedStorageExecutor executor = new BoundedStorageExecutor(1)) {
            CompletableFuture<String> first = executor.submit(() -> { started.countDown(); release.await(); return Thread.currentThread().getName(); });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.submit(() -> 2);
            CompletableFuture<Integer> rejected = executor.submit(() -> 3);
            try {
                assertTrue(rejected.isCompletedExceptionally()); assertThrows(CompletionException.class, rejected::join);
                assertEquals(1, executor.queuedTasks());
            } finally { release.countDown(); }
            assertEquals("GuardianAC-storage", first.get(3, TimeUnit.SECONDS)); assertEquals(2, queued.get(3, TimeUnit.SECONDS));
        }
    }
    @Test void closingFromInlineWorkerDoesNotDeadlockAndErrorsCompleteFuture() {
        try (BoundedStorageExecutor executor = new BoundedStorageExecutor(4)) {
            assertThrows(CompletionException.class, () -> executor.submit(() -> { executor.close(); return 1; }).join());
            assertThrows(CompletionException.class, () -> executor.submit(() -> { throw new AssertionError("test"); }).join());
            assertEquals(42, executor.submit(() -> 42).join());
        }
    }
    @Test void failedJobDoesNotKillQueueAndShutdownRejects() {
        BoundedStorageExecutor executor = new BoundedStorageExecutor(4);
        assertThrows(CompletionException.class, () -> executor.submit(() -> { throw new IllegalStateException("test"); }).join());
        assertEquals(42, executor.submit(() -> 42).join()); executor.close();
        assertThrows(CompletionException.class, () -> executor.submit(() -> 3).join());
    }
}
