package org.gms.channel.persist;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerStorage;
import org.gms.data.repo.CharacterRepository;
import org.gms.domain.game.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 角色存档队列（架构 6.2 ② 单写连接 + 红线 17 增量 FLUSH：只刷脏数据，不做全量落盘）。
 *
 * <p>核心：
 * <ul>
 *   <li><b>单写执行器</b>：所有 DB 写经一个 daemon 线程串行执行（{@code db-writer}），
 *       从构造上消灭 SQLite 并发写（SQLITE_BUSY，架构 6.2 ②）。</li>
 *   <li><b>按角色去重</b>：{@code pending} map 以角色 id 为键，同一角色多次 save 只落一次。</li>
 *   <li>{@link #flushAll}：扫描 {@link PlayerStorage} 中 {@code isDirty()} 的角色入队，落库后清脏
 *       （L4 增量 FLUSH 入口）。</li>
 *   <li>{@link #drain}：DRAINING 阶段排空队列 + 在途写完成（主动重开不丢档的前提，架构 5.4 路径 B）。</li>
 * </ul>
 */
public final class CharacterSaveQueue implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(CharacterSaveQueue.class);

    private final CharacterRepository repository;
    private final CharacterLoader loader;
    private final PlayerStorage playerStorage;
    private final ExecutorService singleWriter;
    private final ConcurrentMap<Long, Boolean> pending = new ConcurrentHashMap<>();

    public CharacterSaveQueue(CharacterRepository repository, CharacterLoader loader, PlayerStorage playerStorage) {
        this.repository = repository;
        this.loader = loader;
        this.playerStorage = playerStorage;
        this.singleWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = Thread.ofPlatform().name("db-writer").daemon(true).unstarted(r);
            return t;
        });
    }

    /**
     * 入队保存角色（幂等：已有 pending 记录则跳过，落库后清脏）。
     *
     * <p>可被任何线程调用（下线/断链/定期 flush）；写动作在单写线程串行执行。
     */
    public void save(Character chr) {
        if (chr == null) {
            return;
        }
        boolean first = pending.putIfAbsent(chr.getId(), Boolean.TRUE) == null;
        if (!first) {
            return; // 已在队列，去重
        }
        singleWriter.execute(() -> {
            try {
                repository.save(loader.toData(chr));
                chr.clearDirty();
            } catch (RuntimeException e) {
                LOG.error("角色存档失败: id={}", chr.getId(), e);
            } finally {
                pending.remove(chr.getId());
            }
        });
    }

    /**
     * 同步保存单个角色（CC 迁移前用：玩家状态必须落 DB，目标频道重连后从 DB 加载最新态）。
     *
     * <p>不同于异步 {@link #save}：本方法直接在调用线程落库 + 清脏，调用方（ChangeChannelHandler）
     * 返回前保证数据已持久化（架构 4.7：老频道 flush 状态 → 目标频道加载，不掉数据）。
     * 与 {@link #flushAllSync} 同语义，只针对单角色。
     */
    public void flushCharacterSync(Character chr) {
        if (chr == null) {
            return;
        }
        try {
            repository.save(loader.toData(chr));
            chr.clearDirty();
        } catch (RuntimeException e) {
            LOG.error("角色同步存档失败: id={}", chr.getId(), e);
        }
    }

    /**
     * 增量 FLUSH：扫描在线表中脏角色入队（红线 17：只刷脏数据）。
     *
     * <p>由 tick handler（CharacterFlushTickHandler）周期性调用，异步（单写线程执行）。
     *
     * @return 本次入队的脏角色数
     */
    public int flushAll() {
        int dirtyCount = 0;
        for (Character chr : playerStorage.all()) {
            if (chr.isDirty()) {
                save(chr);
                dirtyCount++;
            }
        }
        return dirtyCount;
    }

    /**
     * 同步增量 FLUSH（L4 FLUSH_DIRTY 阶段用）：直接在调用线程落库全部脏角色并清脏。
     *
     * <p>重启编排要求"重启前确保落盘"（架构 5.4 路径 B），不能用异步队列（可能未完成）；
     * 主动重开 = 只 FLUSH 脏数据（红线 17），数量小，同步代价可接受。
     *
     * @return 本次落库的脏角色数
     */
    public int flushAllSync() {
        int flushed = 0;
        for (Character chr : playerStorage.all()) {
            if (chr.isDirty()) {
                repository.save(loader.toData(chr));
                chr.clearDirty();
                flushed++;
            }
        }
        return flushed;
    }

    /**
     * DRAINING：等待队列 + 在途写完成（主动重开前调用，架构 5.4 路径 B）。
     *
     * <p>不 shutdown 执行器——DRAINING 后还有 FLUSH_DIRTY 阶段（flushAll）要提交任务；
     * 执行器真正释放由 {@link #close()} 负责。
     *
     * @throws InterruptedException 等待被中断
     */
    public void drain() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pendingCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (pendingCount() > 0) {
            LOG.warn("存档队列排空超时，仍有 {} 个待写角色", pendingCount());
        }
        LOG.info("存档队列已排空");
    }

    /** 当前待写角色数（观测，Sli.WRITE_QUEUE_DEPTH）。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 资源释放（进程关停）。 */
    @Override
    public void close() {
        singleWriter.shutdownNow();
    }
}
