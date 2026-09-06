package org.gms.tick;


import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;

/**
 * 公共时钟单线程实现。频道业务由各自执行队列处理；暂停时钟只排空当前时钟回调，
 * 频道状态任务还需要所属队列的屏障。
 *
 * <p>线程为 daemon（不阻塞 JVM 退出）、名为 {@code game-tick}。单 handler 异常不中断循环
 * （记录 {@link #lastTickError()} 后继续），单个逻辑出错不拖垮整服。
 *
 * <p>{@link #pause()} 在安全点暂停：当前 tick 完成后不再启动下一个，循环空转直到
 * {@link #resume()}。L3 热重载换代流程 = pause → unregister 旧 → register 新 → resume。
 */
@Log4j2
public final class GameTickLoop implements TickScheduler {

    private final long intervalMillis;
    private final CopyOnWriteArrayList<TickHandler> handlers = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCount = new AtomicLong();
    private volatile boolean running;
    private volatile boolean paused;
    private volatile Thread thread;
    private volatile Throwable lastTickError;
    /** 仅在短的准入/完成区间持有本对象监视器，业务回调在监视器外执行。 */
    private Thread activeTick;

    public GameTickLoop(long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException(I18n.message("error.tick.invalid_interval", intervalMillis));
        }
        this.intervalMillis = intervalMillis;
    }

    @Override
    public long intervalMillis() {
        return intervalMillis;
    }

    @Override
    public synchronized void register(TickHandler handler) {
        handlers.add(handler);
    }

    @Override
    public synchronized void unregister(TickHandler handler) {
        handlers.remove(handler);
    }

    @Override
    public int handlerCount() {
        return handlers.size();
    }

    @Override
    public synchronized void start() {
        if (thread != null) {
            return; // 已启动，幂等
        }
        running = true;
        thread = Thread.ofPlatform().name("game-tick").daemon(true).start(this::loop);
        log.info(I18n.message("log.tick.started"), intervalMillis);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        log.info(I18n.message("log.tick.stopped"), tickCount.get());
    }

    @Override
    public synchronized void pause() {
        if (activeTick == Thread.currentThread())
            throw new IllegalStateException(I18n.message("error.tick.self_pause"));
        paused = true;
        while (activeTick != null) {
            try { wait(); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(I18n.message("error.tick.pause_interrupted"), error);
            }
        }
        log.info(I18n.message("log.tick.paused"), tickCount.get());
    }

    @Override
    public synchronized void resume() {
        paused = false;
        log.info(I18n.message("log.tick.resumed"), tickCount.get());
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public long tickCount() {
        return tickCount.get();
    }

    private void loop() {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
        long nextTickAtNanos = System.nanoTime();
        while (running) {
            try {
                if (!paused) {
                    tickOnce();
                }
                nextTickAtNanos += intervalNanos;
                long sleepNanos = nextTickAtNanos - System.nanoTime();
                if (sleepNanos <= 0) {
                    // 本轮超出预算时不追赶积压，从当前时刻重新建立下一周期。
                    nextTickAtNanos = System.nanoTime() + intervalNanos;
                    sleepNanos = intervalNanos;
                }
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // stop() 触发
            }
        }
    }

    /**
     * 执行一次 tick（供测试手动驱动；并发唤醒合并，暂停后不再准入）。
     */
    public void tickOnce() {
        synchronized (this) {
            if (paused || activeTick != null) return;
            activeTick = Thread.currentThread();
        }
        try { dispatchTick(); }
        finally {
            synchronized (this) { activeTick = null; notifyAll(); }
        }
    }

    private void dispatchTick() {
        long count = tickCount.incrementAndGet();
        for (TickHandler handler : handlers) {
            try {
                handler.tick(count);
            } catch (RuntimeException e) {
                lastTickError = e;
                log.error(I18n.message("log.tick.handler_error"), e);
            }
        }
    }

    /** 最近一次 handler 异常（测试/告警用）。 */
    public Throwable lastTickError() {
        return lastTickError;
    }
}
