package org.gms.channel.persist;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.observability.Metrics;
import org.gms.observability.Sli;
import org.gms.tick.TickHandler;

/**
 * 定期增量 FLUSH tick handler（L4：周期性把脏角色刷库，红线 17 只刷脏数据）。
 *
 * <p>注册进 GameTickLoop，每 {@code everyTicks} tick 调 {@link CharacterSaveQueue#flushAll()}。
 * 满足红线 12（逻辑无状态——只读状态 → 入队，不持有跨 tick 状态）。
 */
public final class CharacterFlushTickHandler implements TickHandler {

    private static final Logger LOG = LogManager.getLogger(CharacterFlushTickHandler.class);

    /** 每 N tick 刷一次（10 tick × 100ms = 1s）。 */
    private static final int EVERY_TICKS = 10;

    private final CharacterSaveQueue saveQueue;
    private final Metrics metrics;

    public CharacterFlushTickHandler(CharacterSaveQueue saveQueue, Metrics metrics) {
        this.saveQueue = saveQueue;
        this.metrics = metrics;
    }

    @Override
    public void tick(long tickCount) {
        if (tickCount % EVERY_TICKS == 0) {
            int dirty = saveQueue.flushAll();
            if (dirty > 0) {
                LOG.debug("增量 FLUSH: {} 个脏角色入队", dirty);
            }
            metrics.gauge(Sli.WRITE_QUEUE_DEPTH, saveQueue.pendingCount());
        }
    }
}
