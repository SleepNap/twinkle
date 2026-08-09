package org.gms.channel.persist;

import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerStorage;
import org.gms.data.repo.CharacterRepository;
import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
}
