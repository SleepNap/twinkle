package org.gms.channel.persist;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.gms.channel.PlayerCharacterAssembler;
import org.gms.channel.PlayerStorage;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.i18n.I18n;
import org.gms.i18n.ResourceBundleI18nService;
import org.gms.persistence.entity.InventoryItemEntity;
import org.gms.persistence.repo.InventoryItemRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;




/**
 * 存档队列单测（架构 6.2 ② 单写 + 红线 17 增量 FLUSH）。
 *
 * <p>用内存 repo（记录 save 调用）验证：save 去重、flushAll 只落脏角色、drain 排空。
 */
class CharacterSaveQueueTest {

    /** 内存 repo：记录 save 的 data.PlayerCharacter 快照。 */
    static final class MemoryRepo implements PlayerCharacterRepository {
        final List<org.gms.persistence.entity.PlayerCharacterRecord> saved = new ArrayList<>();
        final AtomicInteger saveCalls = new AtomicInteger();

        @Override
        public List<org.gms.persistence.entity.PlayerCharacterRecord> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override
        public Optional<org.gms.persistence.entity.PlayerCharacterRecord> findById(long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(org.gms.persistence.entity.PlayerCharacterRecord chr) {
            saved.add(chr);
        }

        @Override
        public void save(org.gms.persistence.entity.PlayerCharacterRecord chr) {
            saved.add(chr);
            saveCalls.incrementAndGet();
        }
    }

    static final class MemoryInventoryRepo implements InventoryItemRepository {
        volatile long characterId;
        volatile List<InventoryItemEntity> items = List.of();

        @Override
        public List<InventoryItemEntity> findByCharacterId(long characterId) {
            return items;
        }

        @Override
        public void insert(InventoryItemEntity item) {
        }

        @Override
        public void replaceAll(long characterId, List<InventoryItemEntity> items) {
            this.characterId = characterId;
            this.items = List.copyOf(items);
        }
    }

    static final class BlockingRepo implements PlayerCharacterRepository {
        final List<org.gms.persistence.entity.PlayerCharacterRecord> saved = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean block = true;

        @Override
        public List<org.gms.persistence.entity.PlayerCharacterRecord> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override
        public Optional<org.gms.persistence.entity.PlayerCharacterRecord> findById(long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(org.gms.persistence.entity.PlayerCharacterRecord chr) {
            saved.add(chr);
        }

        @Override
        public void save(org.gms.persistence.entity.PlayerCharacterRecord chr) {
            saved.add(chr);
            if (block) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待测试释放存档超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("存档测试被中断", e);
                }
            }
        }
    }

    static final class FlakyRepo implements PlayerCharacterRepository {
        final AtomicBoolean failing = new AtomicBoolean(true);

        @Override
        public List<org.gms.persistence.entity.PlayerCharacterRecord> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override
        public Optional<org.gms.persistence.entity.PlayerCharacterRecord> findById(long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(org.gms.persistence.entity.PlayerCharacterRecord chr) {
        }

        @Override
        public void save(org.gms.persistence.entity.PlayerCharacterRecord chr) {
            if (failing.get()) {
                throw new IllegalStateException("database unavailable");
            }
        }
    }

    private PlayerCharacter newChar(long id, int meso) {
        PlayerCharacter chr = new PlayerCharacter(new DefaultVersionGate().currentVersion());
        chr.setId(id);
        chr.setName("Hero" + id);
        chr.setMeso(meso);
        chr.setMap(100000000);
        return chr;
    }

    @Test
    void savePersistsAndClearsDirty() throws Exception {
        MemoryRepo repo = new MemoryRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            PlayerCharacter chr = newChar(1, 500);
            chr.markDirty();
            queue.save(chr);
            queue.drain();

            assertThat(repo.saved).hasSize(1);
            assertThat(repo.saved.get(0).getMeso()).isEqualTo(500);
            assertThat(chr.isDirty()).isFalse(); // 落库后清脏
        }
    }

    @Test
    void flushAllOnlyPersistsDirtyCharacters() throws Exception {
        MemoryRepo repo = new MemoryRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            PlayerCharacter dirty = newChar(1, 100);
            dirty.markDirty();
            PlayerCharacter clean = newChar(2, 200);
            clean.clearDirty(); // 模拟已落盘角色（加载后清脏），flushAll 不应刷它
            players.add(dirty);
            players.add(clean);

            int flushed = queue.flushAll();
            queue.drain();

            assertThat(flushed).isEqualTo(1); // 只刷脏角色（红线 17）
            assertThat(repo.saved).hasSize(1);
            assertThat(repo.saved.get(0).getId()).isEqualTo(1);
            assertThat(dirty.isDirty()).isFalse();
            assertThat(clean.isDirty()).isFalse();
        }
    }

    @Test
    void duplicateSaveDeduplicated() throws Exception {
        MemoryRepo repo = new MemoryRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            PlayerCharacter chr = newChar(1, 300);
            chr.markDirty();
            queue.save(chr);
            queue.save(chr); // 去重：同角色多次 save 只落一次
            queue.save(chr);
            queue.drain();

            assertThat(repo.saved).hasSize(1);
            assertThat(repo.saveCalls.get()).isEqualTo(1);
        }
    }

    @Test
    void saveWithoutDirtyStillPersists() throws Exception {
        // 断链下线路径：即使没显式标脏也保存（离线前保证落库）
        MemoryRepo repo = new MemoryRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            PlayerCharacter chr = newChar(1, 42);
            queue.save(chr);
            queue.drain();
            assertThat(repo.saved).hasSize(1);
        }
    }

    @Test
    void savePersistsCompleteInventorySnapshot() throws Exception {
        MemoryRepo repo = new MemoryRepo();
        MemoryInventoryRepo inventoryRepo = new MemoryInventoryRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate(), inventoryRepo);
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, inventoryRepo, loader, players)) {
            PlayerCharacter chr = newChar(8, 42);
            chr.setAccountId(3L);
            Item potion = new Item(2000000);
            potion.setQuantity((short) 25);
            chr.getInventory(InventoryType.USE).addItem(potion);
            chr.markDirty();

            queue.save(chr);
            queue.drain();

            assertThat(inventoryRepo.characterId).isEqualTo(8L);
            assertThat(inventoryRepo.items).singleElement().satisfies(saved -> {
                assertThat(saved.getItemId()).isEqualTo(2000000);
                assertThat(saved.getInventoryType()).isEqualTo(InventoryType.USE.getType());
                assertThat(saved.getPosition()).isEqualTo(1);
                assertThat(saved.getQuantity()).isEqualTo(25);
                assertThat(saved.getCharacterId()).isEqualTo(8);
                assertThat(saved.getAccountId()).isEqualTo(3);
            });
            assertThat(chr.isDirty()).isFalse();
        }
    }

    @Test
    void mutationDuringAsyncSaveRemainsDirtyForNextFlush() throws Exception {
        BlockingRepo repo = new BlockingRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            PlayerCharacter chr = newChar(11, 100);
            queue.save(chr);
            assertThat(repo.entered.await(5, TimeUnit.SECONDS)).isTrue();

            chr.setMeso(200);
            repo.release.countDown();
            queue.drain();

            assertThat(repo.saved).singleElement().extracting(org.gms.persistence.entity.PlayerCharacterRecord::getMeso)
                    .isEqualTo(100);
            assertThat(chr.isDirty()).isTrue();

            repo.block = false;
            queue.save(chr);
            queue.drain();
            assertThat(repo.saved).extracting(org.gms.persistence.entity.PlayerCharacterRecord::getMeso)
                    .containsExactly(100, 200);
            assertThat(chr.isDirty()).isFalse();
        }
    }

    @Test
    void failedDisconnectedCharacterBlocksDrainUntilSynchronousRetrySucceeds() throws Exception {
        I18n.install(new ResourceBundleI18nService("zh-CN"));
        FlakyRepo repo = new FlakyRepo();
        PlayerCharacterAssembler loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            PlayerCharacter chr = newChar(17, 700);
            chr.markDirty();
            queue.save(chr);

            assertThatThrownBy(queue::drain)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("17");
            assertThat(queue.failedCharacterIds()).containsExactly(17L);
            assertThat(chr.isDirty()).isTrue();

            repo.failing.set(false);
            queue.drain();

            assertThat(queue.failedCharacterIds()).isEmpty();
            assertThat(chr.isDirty()).isFalse();
        } finally {
            I18n.install(null);
        }
    }
    @Test
    void overloadIsBoundedAndDisconnectedCharactersAreRetried() throws Exception {
        BlockingRepo repo = new BlockingRepo();
        var loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        try (var queue = new CharacterSaveQueue(repo, loader, new PlayerStorage(), 1)) {
            PlayerCharacter first = newChar(41, 100), second = newChar(42, 200);
            first.markDirty(); second.markDirty();
            var saved = queue.saveAsync(first);
            assertThat(repo.entered.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(queue.saveAsync(first)).isSameAs(saved);
                assertThat(queue.saveAsync(second)).isCompletedExceptionally();
                assertThat(queue.pendingCount()).isEqualTo(1);
                assertThat(queue.status().deferredCharacters()).isEqualTo(1);
                assertThat(queue.awaitStored(99)).isCompletedExceptionally();
                assertThat(second.isDirty()).isTrue();
            } finally { repo.block = false; repo.release.countDown(); }
            saved.get(2, TimeUnit.SECONDS);
            queue.drain();
            assertThat(repo.saved).extracting(org.gms.persistence.entity.PlayerCharacterRecord::getId).containsExactly(41L, 42L);
            assertThat(queue.failedCharacterIds()).isEmpty();
            assertThat(second.isDirty()).isFalse();
            assertThat(queue.awaitStored(99)).isCompleted();
        }
    }

    @Test
    void lateSnapshotCannotOverwriteNewerCommittedData() throws Exception {
        var repo = new MemoryRepo();
        var loader = new PlayerCharacterAssembler(new DefaultVersionGate());
        try (var queue = new CharacterSaveQueue(repo, loader, new PlayerStorage())) {
            var character = newChar(51, 100); character.markDirty();
            // 精确模拟“旧快照已生成，但其完成回调比新快照晚到”的调度交错。
            var capture = CharacterSaveQueue.class.getDeclaredMethod("capture", PlayerCharacter.class);
            capture.setAccessible(true);
            Object old = capture.invoke(queue, character);
            character.setMeso(200); character.markDirty();
            queue.saveAsync(character).get(2, TimeUnit.SECONDS);
            var enqueue = CharacterSaveQueue.class.getDeclaredMethod("enqueueSnapshot", old.getClass());
            enqueue.setAccessible(true);
            ((java.util.concurrent.CompletableFuture<?>) enqueue.invoke(queue, old)).get(2, TimeUnit.SECONDS);
            assertThat(repo.saved).extracting(org.gms.persistence.entity.PlayerCharacterRecord::getMeso).containsExactly(200);
        }
    }

}
