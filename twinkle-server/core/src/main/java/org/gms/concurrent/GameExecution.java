package org.gms.concurrent;

import lombok.extern.log4j.Log4j2;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.hotreload.versioned.VersionScope;

import org.gms.i18n.I18n;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.Objects;

/** 稳定的频道执行归属；网络、Tick 和回调只投递任务，不各自锁游戏对象。 */
@Log4j2
public final class GameExecution implements AutoCloseable {
    private static final ThreadLocal<GameExecution> CURRENT = new ThreadLocal<>();
    private final ThreadPoolExecutor executor;
    private final VersionGate versions;
    private final Semaphore admissions = new Semaphore(4096);
    private long operationVersion;
    private Consumer<Runnable> operationScope;

    public GameExecution(String name, VersionGate versions) {
        this.versions = versions;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                task -> Thread.ofPlatform().name(name).daemon(true).unstarted(task),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean isOwner() { return CURRENT.get() == this; }
    public static boolean inGameOperation() { return CURRENT.get() != null; }
    public static GameExecution current() { return CURRENT.get(); }
    public long version() { return isOwner() ? operationVersion : versions.currentVersion(); }

    public void requireOwner() {
        if (!isOwner()) throw new IllegalStateException(I18n.message("error.execution.foreign_state"));
    }

    /** 稳定宿主在装配时绑定资源作用域；每个完整操作固定资源代际，不能在业务中临时换代。 */
    public void bindOperationScope(Consumer<Runnable> scope) {
        Objects.requireNonNull(scope, "scope");
        run(() -> {
            if (operationScope != null) throw new IllegalStateException(I18n.message("error.execution.scope_bound"));
            operationScope = scope;
        });
    }

    public void execute(Runnable action) {
        submitControl(() -> { action.run(); return null; }).exceptionally(error -> {
            log.error(I18n.message("log.execution.failed"), error);
            return null;
        });
    }

    public <T> CompletableFuture<T> submit(Supplier<T> action) {
        if (!admissions.tryAcquire()) return CompletableFuture.failedFuture(
                new RejectedExecutionException(I18n.message("error.execution.full")));
        return submitControl(action).whenComplete((ignored, error) -> admissions.release());
    }

    /** 已接入任务的清理、存档回调和管理屏障共用 FIFO，不受普通封包配额挤占。 */
    private <T> CompletableFuture<T> submitControl(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                CURRENT.set(this);
                operationVersion = versions.currentVersion();
                try {
                    Runnable operation = () -> VersionScope.call(versions, operationVersion, () -> {
                        result.complete(action.get());
                        return null;
                    });
                    if (operationScope == null) operation.run();
                    else operationScope.accept(operation);
                }
                catch (Throwable error) { result.completeExceptionally(error); }
                finally { CURRENT.remove(); }
            });
        } catch (RejectedExecutionException error) { result.completeExceptionally(error); }
        return result;
    }

    /** 仅供控制面与快照读取等待；禁止游戏线程同步等待另一个频道。 */
    public <T> T call(Supplier<T> action) {
        if (isOwner()) return action.get();
        if (inGameOperation()) throw new IllegalStateException(I18n.message("error.execution.cross_channel_wait"));
        try { return submitControl(action).join(); }
        catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }

    public void run(Runnable action) { call(() -> { action.run(); return null; }); }

    /** 延迟回调携带创建时的版本，旧逻辑的回调不得在新版本入口下重新获得写权限。 */
    public CompletableFuture<Boolean> continueAt(long expectedVersion, Runnable action) {
        return submit(() -> {
            if (operationVersion != expectedVersion) return false;
            action.run();
            return true;
        });
    }

    public int queuedTasks() { return executor.getQueue().size(); }
    public boolean isClosed() { return executor.isShutdown(); }

    @Override public void close() {
        if (isOwner()) throw new IllegalStateException(I18n.message("error.execution.self_close"));
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException(I18n.message("error.execution.drain_timeout"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(I18n.message("error.execution.drain_interrupted"), error);
        }
    }
}
