package org.gms.channel.persist;

import lombok.extern.log4j.Log4j2;
import org.gms.channel.PlayerCharacterAssembler;
import org.gms.channel.ChannelPlayerDirectory;
import org.gms.channel.PlayerStorage;
import org.gms.concurrent.GameExecution;
import org.gms.domain.game.PlayerCharacter;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.entity.InventoryItemEntity;
import org.gms.persistence.entity.QuestStatusEntity;
import org.gms.persistence.entity.SkillEntity;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.persistence.repo.PlayerCharacterSnapshotRepository;
import org.gms.persistence.repo.InventoryItemRepository;
import org.gms.persistence.repo.QuestProgressSnapshot;
import org.gms.i18n.I18n;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 在频道边界取得独立快照，所有保存（含同步等待、失败重试）只经一个写队列。 */
@Log4j2
public final class CharacterSaveQueue implements AutoCloseable {
    private final PlayerCharacterRepository repository;
    private final InventoryItemRepository inventoryItemRepository;
    private final PlayerCharacterSnapshotRepository snapshotRepository;
    private final PlayerCharacterAssembler loader;
    private final Supplier<Collection<PlayerCharacter>> onlinePlayers;
    private final ExecutorService singleWriter;
    private final AtomicInteger pending = new AtomicInteger();
    /** 仅协调同角色、同脏版本的重复请求；不在此锁内读取游戏状态或调用数据库。 */
    private final Map<PlayerCharacter, Attempt> attempts = new WeakHashMap<>();
    private final ConcurrentMap<Long, Snapshot> failed = new ConcurrentHashMap<>();

    private record Attempt(long version, CompletableFuture<Void> completion) { }
    private record Snapshot(PlayerCharacter source, long version, PlayerCharacterRecord character,
                            List<InventoryItemEntity> items, List<QuestStatusEntity> quests,
                            List<QuestProgressSnapshot> progress, List<SkillEntity> skills) { }

    public CharacterSaveQueue(PlayerCharacterRepository repository, PlayerCharacterAssembler loader, PlayerStorage playerStorage) {
        this(repository, null, null, loader, playerStorage::all);
    }

    public CharacterSaveQueue(PlayerCharacterRepository repository, InventoryItemRepository inventoryItemRepository,
                              PlayerCharacterAssembler loader, PlayerStorage playerStorage) {
        this(repository, inventoryItemRepository, null, loader, playerStorage::all);
    }

    /** 生产装配入口：角色与背包经同一事务原子落盘。 */
    public CharacterSaveQueue(PlayerCharacterSnapshotRepository snapshotRepository,
                              PlayerCharacterAssembler loader, PlayerStorage playerStorage) {
        this(null, null, snapshotRepository, loader, playerStorage::all);
    }

    /** 生产 Worker 装配入口：一个单写执行器聚合该进程托管的全部频道。 */
    public CharacterSaveQueue(PlayerCharacterSnapshotRepository snapshotRepository,
                              PlayerCharacterAssembler loader, ChannelPlayerDirectory directory) {
        this(null, null, snapshotRepository, loader, directory::allPlayers);
    }

    private CharacterSaveQueue(PlayerCharacterRepository repository,
                               InventoryItemRepository inventoryItemRepository,
                               PlayerCharacterSnapshotRepository snapshotRepository,
                               PlayerCharacterAssembler loader,
                               Supplier<Collection<PlayerCharacter>> onlinePlayers) {
        this.repository = repository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.snapshotRepository = snapshotRepository;
        this.loader = loader;
        this.onlinePlayers = onlinePlayers;
        this.singleWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = Thread.ofPlatform().name("db-writer").daemon(true).unstarted(r);
            return t;
        });
    }

    public void save(PlayerCharacter character) {
        saveAsync(character).exceptionally(error -> {
            log.error(I18n.message("log.save.async_failed"), error);
            return null;
        });
    }

    /** 完成信号代表事务已经提交；新版本请求不会被旧的 pending 标记吞掉。 */
    public CompletableFuture<Void> saveAsync(PlayerCharacter character) {
        if (character == null) return CompletableFuture.completedFuture(null);
        pending.incrementAndGet();
        GameExecution owner = character.execution();
        CompletableFuture<Snapshot> captured;
        try {
            captured = owner != null && !owner.isOwner()
                    ? owner.submit(() -> capture(character))
                    : CompletableFuture.completedFuture(capture(character));
        } catch (Throwable error) {
            pending.decrementAndGet();
            return CompletableFuture.failedFuture(error);
        }
        return captured.thenCompose(snapshot -> {
            synchronized (attempts) {
                Attempt previous = attempts.get(character);
                if (previous != null && previous.version() >= snapshot.version()
                        && !previous.completion().isCompletedExceptionally()) return previous.completion();
                CompletableFuture<Void> result = write(snapshot);
                attempts.put(character, new Attempt(snapshot.version(), result));
                return result;
            }
        }).whenComplete((ignored, error) -> pending.decrementAndGet());
    }

    private Snapshot capture(PlayerCharacter character) {
        // 未绑定频道的加载态/测试对象也保留一致快照；锁在转换完成后立即释放。
        synchronized (character) {
            return new Snapshot(character, character.dirtyVersion(), loader.toData(character),
                    List.copyOf(loader.toInventoryData(character)), List.copyOf(loader.toQuestStatusData(character)),
                    List.copyOf(loader.toQuestProgressData(character)), List.copyOf(loader.toSkillData(character)));
        }
    }

    private CompletableFuture<Void> write(Snapshot snapshot) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            singleWriter.execute(() -> {
                try {
                    persist(snapshot);
                    result.complete(null);
                } catch (Throwable error) {
                    failed.put(snapshot.character().getId(), snapshot);
                    result.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            failed.put(snapshot.character().getId(), snapshot);
            result.completeExceptionally(error);
        }
        return result;
    }

    /** 只由写线程调用，包括失败重试；失败集合的读取与重写也在该线程内排序。 */
    private void persist(Snapshot snapshot) {
        if (snapshotRepository != null) snapshotRepository.save(snapshot.character(), snapshot.items(),
                snapshot.quests(), snapshot.progress(), snapshot.skills());
        else {
            if (repository != null) repository.save(snapshot.character());
            if (inventoryItemRepository != null)
                inventoryItemRepository.replaceAll(snapshot.character().getId(), snapshot.items());
        }
        snapshot.source().clearDirty(snapshot.version());
        failed.remove(snapshot.character().getId());
    }

    /** 只供控制面等待；游戏操作必须用 saveAsync 的完成回调继续，不能堵住频道。 */
    public void flushCharacterSync(PlayerCharacter character) {
        requireOutsideGame();
        await(saveAsync(character));
    }

    /** 登录读取前等待已排入的离线存档；失败未补存时拒绝读取旧档。 */
    public CompletableFuture<Void> awaitStored(long characterId) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            singleWriter.execute(() -> {
                if (failed.containsKey(characterId)) completion.completeExceptionally(
                        new IllegalStateException(I18n.message("error.save.pending_failure", characterId)));
                else completion.complete(null);
            });
        } catch (RuntimeException error) { completion.completeExceptionally(error); }
        return completion;
    }

    public int flushAll() {
        int count = 0;
        for (PlayerCharacter character : onlinePlayers.get()) {
            if (character.isDirty()) { save(character); count++; }
        }
        return count;
    }

    public int flushAllSync() {
        requireOutsideGame();
        List<CompletableFuture<Void>> jobs = onlinePlayers.get().stream().filter(PlayerCharacter::isDirty)
                .map(this::saveAsync).toList();
        jobs.forEach(CharacterSaveQueue::await);
        return jobs.size();
    }

    public void drain() throws InterruptedException {
        requireOutsideGame();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pending.get() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        if (pending.get() > 0) throw new IllegalStateException(I18n.message("error.save.drain_timeout"));
        CompletableFuture<Void> retried = new CompletableFuture<>();
        singleWriter.execute(() -> {
            for (Snapshot snapshot : List.copyOf(failed.values())) {
                try { persist(snapshot); }
                catch (Throwable error) { log.error(I18n.message("log.save.retry_failed"), error); }
            }
            retried.complete(null);
        });
        await(retried);
        if (!failed.isEmpty()) throw new IllegalStateException(
                I18n.message("error.save.persist_failed", failedCharacterIds()));
    }

    private static void requireOutsideGame() {
        if (GameExecution.inGameOperation()) throw new IllegalStateException(I18n.message("error.save.game_wait"));
    }

    private static void await(CompletableFuture<Void> completion) {
        try { completion.join(); }
        catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        }
    }

    public int pendingCount() { return pending.get(); }
    public List<Long> failedCharacterIds() { return failed.keySet().stream().sorted().toList(); }

    @Override public void close() {
        requireOutsideGame();
        try {
            drain();
            singleWriter.shutdown();
            if (!singleWriter.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException(I18n.message("error.save.writer_timeout"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(I18n.message("error.save.interrupted"), error);
        } finally { singleWriter.shutdown(); }
    }
}
