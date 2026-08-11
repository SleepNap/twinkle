package org.gms.channel.persist;

import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerStorage;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存档队列单测（架构 6.2 ② 单写 + 红线 17 增量 FLUSH）。
 *
 * <p>用内存 repo（记录 save 调用）验证：save 去重、flushAll 只落脏角色、drain 排空。
 */
class CharacterSaveQueueTest {

    /** 内存 repo：记录 save 的 data.Character 快照。 */
    static final class MemoryRepo implements CharacterRepository {
        final List<org.gms.data.entity.Character> saved = new ArrayList<>();
        final AtomicInteger saveCalls = new AtomicInteger();

        @Override
        public List<org.gms.data.entity.Character> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override
        public Optional<org.gms.data.entity.Character> findById(long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(org.gms.data.entity.Character chr) {
            saved.add(chr);
        }

        @Override
        public void save(org.gms.data.entity.Character chr) {
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

    static final class BlockingRepo implements CharacterRepository {
        final List<org.gms.data.entity.Character> saved = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean block = true;

        @Override
        public List<org.gms.data.entity.Character> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override
        public Optional<org.gms.data.entity.Character> findById(long id) {
            return Optional.empty();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(org.gms.data.entity.Character chr) {
            saved.add(chr);
        }

        @Override
        public void save(org.gms.data.entity.Character chr) {
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

    private Character newChar(long id, int meso) {
        Character chr = new Character(new DefaultVersionGate().currentVersion());
        chr.setId(id);
        chr.setName("Hero" + id);
        chr.setMeso(meso);
        chr.setMap(100000000);
        return chr;
    }

    @Test
    void savePersistsAndClearsDirty() throws Exception {
        MemoryRepo repo = new MemoryRepo();
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            Character chr = newChar(1, 500);
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
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            Character dirty = newChar(1, 100);
            dirty.markDirty();
            Character clean = newChar(2, 200);
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
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            Character chr = newChar(1, 300);
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
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            Character chr = newChar(1, 42);
            queue.save(chr);
            queue.drain();
            assertThat(repo.saved).hasSize(1);
        }
    }

    @Test
    void savePersistsCompleteInventorySnapshot() throws Exception {
        MemoryRepo repo = new MemoryRepo();
        MemoryInventoryRepo inventoryRepo = new MemoryInventoryRepo();
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate(), inventoryRepo);
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, inventoryRepo, loader, players)) {
            Character chr = newChar(8, 42);
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
        CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());
        PlayerStorage players = new PlayerStorage();
        try (CharacterSaveQueue queue = new CharacterSaveQueue(repo, loader, players)) {
            Character chr = newChar(11, 100);
            queue.save(chr);
            assertThat(repo.entered.await(5, TimeUnit.SECONDS)).isTrue();

            chr.setMeso(200);
            repo.release.countDown();
            queue.drain();

            assertThat(repo.saved).singleElement().extracting(org.gms.data.entity.Character::getMeso)
                    .isEqualTo(100);
            assertThat(chr.isDirty()).isTrue();

            repo.block = false;
            queue.save(chr);
            queue.drain();
            assertThat(repo.saved).extracting(org.gms.data.entity.Character::getMeso)
                    .containsExactly(100, 200);
            assertThat(chr.isDirty()).isFalse();
        }
    }
}
