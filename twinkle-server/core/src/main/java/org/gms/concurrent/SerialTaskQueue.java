package org.gms.concurrent;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


/** 有界 FIFO 后台任务链；执行器只运行一个排空任务，失败不会截断后续工作。 */
public final class SerialTaskQueue implements AutoCloseable {
    private final Executor executor;
    private final int capacity;
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private int pending;
    private boolean closed;
    public SerialTaskQueue(Executor executor, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.executor = executor;
        this.capacity = capacity;
    }
    public synchronized <T> CompletableFuture<T> submit(Supplier<T> action) {
        if (closed || pending >= capacity) return CompletableFuture.failedFuture(new RejectedExecutionException("Control IO queue unavailable"));
        CompletableFuture<T> result = new CompletableFuture<>();
        tasks.add(() -> {
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        if (++pending == 1) {
            try { executor.execute(this::drain); }
            catch (RuntimeException error) { tasks.clear(); pending = 0; result.completeExceptionally(error); }
        }
        return result;
    }
    public CompletableFuture<Void> run(Runnable action) { return submit(() -> { action.run(); return null; }); }
    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) { task = tasks.poll(); }
            task.run();
            synchronized (this) { if (--pending == 0) { notifyAll(); return; } }
        }
    }
    public synchronized int pendingCount() { return pending; }
    public synchronized void awaitIdle(Duration timeout) {
        if (GameExecution.inGameOperation()) throw new IllegalStateException("Cannot wait for IO on game thread");
        long end = System.nanoTime() + timeout.toNanos();
        while (pending > 0) {
            long left = end - System.nanoTime();
            if (left <= 0) throw new IllegalStateException("Control IO drain timeout");
            try { TimeUnit.NANOSECONDS.timedWait(this, left); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }
    }
    @Override public void close() {
        synchronized (this) { closed = true; }
        awaitIdle(Duration.ofSeconds(5));
    }
}
