package org.gms.tick;


import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.log4j.Log4j2;

/**
 * 游戏循环单线程实现（架构 5.1：游戏 tick 单线程，换点干净——tick 帧边界暂停、卸载、
 * 加载新 classloader、重注册、恢复，无并发执行中的逻辑）。
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

    public GameTickLoop(long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis 必须 > 0: " + intervalMillis);
        }
        this.intervalMillis = intervalMillis;
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
        log.info("GameTickLoop 启动，间隔 {}ms", intervalMillis);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        log.info("GameTickLoop 停止，共 {} ticks", tickCount.get());
    }

    @Override
    public synchronized void pause() {
        paused = true;
        log.info("GameTickLoop 暂停（安全点，tick={}）", tickCount.get());
    }

    @Override
    public synchronized void resume() {
        paused = false;
        log.info("GameTickLoop 恢复（tick={}）", tickCount.get());
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
        while (running) {
            try {
                if (!paused) {
                    tickOnce();
                }
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // stop() 触发
            }
        }
    }

    /**
     * 执行一次 tick（package-private，供测试手动驱动；循环线程也调用它）。
     */
    public void tickOnce() {
        long count = tickCount.incrementAndGet();
        for (TickHandler handler : handlers) {
            try {
                handler.tick(count);
            } catch (RuntimeException e) {
                lastTickError = e;
                log.error("tick handler 异常（记录并继续）", e);
            }
        }
    }

    /** 最近一次 handler 异常（测试/告警用）。 */
    public Throwable lastTickError() {
        return lastTickError;
    }
}
