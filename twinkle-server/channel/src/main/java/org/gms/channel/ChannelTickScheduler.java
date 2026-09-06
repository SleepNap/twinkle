package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.GameExecution;
import org.gms.i18n.I18n;
import org.gms.tick.TickHandler;
import org.gms.tick.TickScheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 公共时钟只负责唤醒；每个频道的业务 Tick 在自己的执行队列中运行。 */
@Log4j2
public final class ChannelTickScheduler implements TickScheduler, AutoCloseable {
    private final TickScheduler clock;
    private final GameExecution execution;
    private final Map<TickHandler, TickHandler> registrations = new ConcurrentHashMap<>();

    public ChannelTickScheduler(TickScheduler clock, GameExecution execution) {
        this.clock = clock;
        this.execution = execution;
    }

    @Override public void register(TickHandler handler) {
        AtomicBoolean pending = new AtomicBoolean();
        AtomicLong delivered = new AtomicLong();
        TickHandler wakeup = count -> {
            if (!pending.compareAndSet(false, true)) return;
            execution.submit(() -> {
                try { if (registrations.containsKey(handler)) handler.tick(delivered.incrementAndGet()); }
                finally { pending.set(false); }
                return null;
            }).exceptionally(error -> {
                pending.set(false);
                log.error(I18n.message("log.execution.tick_failed"), error);
                return null;
            });
        };
        if (registrations.putIfAbsent(handler, wakeup) == null) clock.register(wakeup);
    }

    @Override public void unregister(TickHandler handler) {
        TickHandler wakeup = registrations.remove(handler);
        if (wakeup != null) clock.unregister(wakeup);
    }
    @Override public int handlerCount() { return registrations.size(); }
    @Override public long intervalMillis() { return clock.intervalMillis(); }
    @Override public long tickCount() { return clock.tickCount(); }
    @Override public void start() { clock.start(); }
    @Override public void stop() { clock.stop(); }
    @Override public void pause() { clock.pause(); execution.run(() -> { }); }
    @Override public void resume() { clock.resume(); }
    @Override public boolean isPaused() { return clock.isPaused(); }
    @Override public void close() { registrations.keySet().forEach(this::unregister); }
}
