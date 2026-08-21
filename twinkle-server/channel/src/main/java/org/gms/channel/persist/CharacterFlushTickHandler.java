package org.gms.channel.persist;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.observability.Metrics;
import org.gms.observability.Sli;
import org.gms.tick.TickHandler;

/**
 * 定期增量 FLUSH tick handler（L4：周期性把脏角色刷库，红线 17 只刷脏数据）。
 *
 * <p>注册进 GameTickLoop，每 {@code everyTicks} tick 调 {@link CharacterSaveQueue#flushAll()}。
 * 满足红线 12（逻辑无状态——只读状态 → 入队，不持有跨 tick 状态）。
 */
@Log4j2
public final class CharacterFlushTickHandler implements TickHandler {



    private final CharacterSaveQueue saveQueue;
    private final Metrics metrics;
    private final long everyTicks;

    public CharacterFlushTickHandler(CharacterSaveQueue saveQueue, Metrics metrics, long everyTicks) {
        if (everyTicks <= 0) {
            throw new IllegalArgumentException("everyTicks must be positive");
        }
        this.saveQueue = saveQueue;
        this.metrics = metrics;
        this.everyTicks = everyTicks;
    }

    @Override
    public void tick(long tickCount) {
        if (tickCount % everyTicks == 0) {
            int dirty = saveQueue.flushAll();
            if (dirty > 0) {
                log.debug(I18n.message("log.save.flush_dirty"), dirty);
            }
            metrics.gauge(Sli.WRITE_QUEUE_DEPTH, saveQueue.pendingCount());
        }
    }
}
