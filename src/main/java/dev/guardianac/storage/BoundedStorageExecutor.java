// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serial, bounded and Bukkit-free. Rejection never silently discards a punishment/evidence write. */
public final class BoundedStorageExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private volatile Thread workerThread;
    public BoundedStorageExecutor(int capacity) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Invalid queue capacity");
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), runnable -> {
            Thread thread = new Thread(runnable, "GuardianAC-storage"); thread.setDaemon(true); workerThread = thread; return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public <T> CompletableFuture<T> submit(Callable<T> action) {
        Job<T> job = new Job<>(Objects.requireNonNull(action));
        try { executor.execute(job); }
        catch (RejectedExecutionException ignored) { job.future.completeExceptionally(new StorageException("Storage queue is full or closed; action was not queued")); }
        return job.future;
    }
    boolean isWorkerThread() { return Thread.currentThread() == workerThread; }
    public int queuedTasks() { return executor.getQueue().size(); }
    @Override public void close() { close(Duration.ofSeconds(10)); }
    public void close(Duration timeout) {
        if (isWorkerThread()) throw new StorageException("Storage must be closed from its lifecycle owner, not an inline completion callback");
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid shutdown timeout");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                for (Runnable pending : executor.shutdownNow())
                    if (pending instanceof Job<?> job) job.future.completeExceptionally(new StorageException("Storage closed before action ran"));
                if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    throw new StorageException("Storage worker did not stop safely");
            }
        } catch (InterruptedException ignored) {
            for (Runnable pending : executor.shutdownNow())
                if (pending instanceof Job<?> job) job.future.completeExceptionally(new StorageException("Storage shutdown interrupted"));
            Thread.currentThread().interrupt(); throw new StorageException("Storage shutdown interrupted");
        }
    }
    private static final class Job<T> implements Runnable {
        private final Callable<T> action;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private Job(Callable<T> action) { this.action = action; }
        @Override public void run() {
            if (future.isCancelled()) return;
            try { future.complete(action.call()); }
            catch (Throwable error) {
                future.completeExceptionally(error);
                if (error instanceof VirtualMachineError fatal) throw fatal;
            }
        }
    }
}
