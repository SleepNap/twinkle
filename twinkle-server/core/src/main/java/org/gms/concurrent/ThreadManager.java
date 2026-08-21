package org.gms.concurrent;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 进程级通用任务执行入口。
 *
 * <p>每个任务独占一个虚拟线程，适合数据库、文件、HTTP/RPC 等可能阻塞但彼此独立的工作。
 * Netty EventLoop、游戏 Tick、定时调度器和单写队列具有线程亲和性、周期性或顺序约束，
 * 不应提交到本管理器。
 */
@Singleton
@Context
public final class ThreadManager implements Executor, AutoCloseable {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong submittedTasks = new AtomicLong();
    private final AtomicLong runningTasks = new AtomicLong();
    private final AtomicLong succeededTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private final AtomicLong rejectedTasks = new AtomicLong();

    public ThreadManager() {
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("twinkle-worker-", 0).factory());
    }

    /** 提交一个无需返回值的独立任务。 */
    @Override
    public void execute(Runnable task) {
        Runnable tracked = track(Objects.requireNonNull(task, "task"));
        try {
            executor.execute(tracked);
        } catch (RuntimeException e) {
            rejectedTasks.incrementAndGet();
            throw e;
        }
    }

    /** 提交一个可等待的独立任务。 */
    public Future<?> submit(Runnable task) {
        Runnable tracked = track(Objects.requireNonNull(task, "task"));
        try {
            return executor.submit(tracked);
        } catch (RuntimeException e) {
            rejectedTasks.incrementAndGet();
            throw e;
        }
    }

    /** 提交一个带返回值、可等待的独立任务。 */
    public <T> Future<T> submit(Callable<T> task) {
        Callable<T> tracked = track(Objects.requireNonNull(task, "task"));
        try {
            return executor.submit(tracked);
        } catch (RuntimeException e) {
            rejectedTasks.incrementAndGet();
            throw e;
        }
    }

    /** 提交一个可组合的异步任务。 */
    public CompletableFuture<Void> runAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<Void> result = new CompletableFuture<>();
        execute(() -> {
            try {
                task.run();
                result.complete(null);
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
                throw e;
            }
        });
        return result;
    }

    /** 提交一个带返回值、可组合的异步任务。 */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        execute(() -> {
            try {
                result.complete(task.get());
            } catch (RuntimeException | Error e) {
                result.completeExceptionally(e);
                throw e;
            }
        });
        return result;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 返回供后台任务监控和后续指标导出的无锁快照。 */
    public Snapshot snapshot() {
        return new Snapshot("virtual-thread-per-task", true, closed.get(), submittedTasks.get(),
                runningTasks.get(), succeededTasks.get(), failedTasks.get(), rejectedTasks.get());
    }

    private Runnable track(Runnable task) {
        submittedTasks.incrementAndGet();
        return () -> {
            runningTasks.incrementAndGet();
            try {
                task.run();
                succeededTasks.incrementAndGet();
            } catch (RuntimeException | Error e) {
                failedTasks.incrementAndGet();
                throw e;
            } finally {
                runningTasks.decrementAndGet();
            }
        };
    }

    private <T> Callable<T> track(Callable<T> task) {
        submittedTasks.incrementAndGet();
        return () -> {
            runningTasks.incrementAndGet();
            try {
                T result = task.call();
                succeededTasks.incrementAndGet();
                return result;
            } catch (Exception | Error e) {
                failedTasks.incrementAndGet();
                throw e;
            } finally {
                runningTasks.decrementAndGet();
            }
        };
    }

    /**
     * 停止接收新任务并等待在途任务收尾；超时后中断剩余任务。
     * Micronaut 关闭容器时自动调用，重复调用安全。
     */
    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 线程执行器监控快照；字段保持纯计数，便于 HTTP、Micrometer 和 Web 复用。 */
    public record Snapshot(String executorType, boolean virtualThreads, boolean closed,
                           long submittedTasks, long runningTasks, long succeededTasks,
                           long failedTasks, long rejectedTasks) {
    }
}
