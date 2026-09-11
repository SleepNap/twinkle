package org.gms.channel;

import org.gms.logic.game.DefaultRewardSystem;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.concurrent.GameExecution;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.repo.PlayerCharacterSnapshotRepository;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult.Status;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯内存角色及隔离存档替身；检验消息隔离、资产原子性和失败后的重复请求。 */
public class RewardDeliveryTest {
    @Test public void onePlayerFailureDoesNotStopAnotherAndRewardsAreAtomic() {
        var gate = new DefaultVersionGate();
        try (var owner = new GameExecution("reward-test", gate)) {
            var players = new PlayerStorage(owner);
            var sessions = new PlayerSessionRegistry(owner);
            var assembler = new PlayerCharacterAssembler(gate);
            var first = new GameplayTestSession(1, map());
            var second = new GameplayTestSession(2, map());
            first.character.setUseSlots((short) 0);
            owner.run(() -> {
                players.add(first.character); players.add(second.character);
                sessions.claim(1, first); sessions.claim(2, second);
            });
            PlayerCharacterSnapshotRepository repository = (character, items, quests, progress, skills) -> { };
            try (var saves = new CharacterSaveQueue(repository, assembler, players)) {
                var service = new RewardDeliveryService(players, sessions,
                        new DefaultRewardSystem(gate, GameDataProvider.fixed(Map.of(2000000, new ItemData(2000000)), Map.of())), saves);
                var a = service.grant(grant(1));
                var b = service.grant(grant(2));
                CompletableFuture.allOf(a, b).join();
                assertThat(a.join().status()).isEqualTo(Status.NO_SPACE);
                assertThat(b.join().status()).isEqualTo(Status.APPLIED);
                owner.run(() -> {
                    assertThat(first.character.getMeso()).isZero();
                    assertThat(first.character.getExp()).isZero();
                    assertThat(first.character.rewardReceipts()).isEmpty();
                    assertThat(second.character.getMeso()).isEqualTo(20);
                    assertThat(second.character.getExp()).isEqualTo(30);
                    assertThat(second.character.getInventory(InventoryType.USE).items()).hasSize(1);
                });
                assertThat(service.grant(grant(2)).join().status()).isEqualTo(Status.ALREADY_APPLIED);
                assertThat(service.grant(new RewardGrant("batch-1", 2, Map.of(), 999, 0)).join().status())
                        .isEqualTo(Status.IDEMPOTENCY_CONFLICT);
            }
        }
    }

    @Test public void persistenceFailureRetriesSavingWithoutGrantingAgainAndReceiptsSurviveLoading() {
        var gate = new DefaultVersionGate();
        try (var owner = new GameExecution("reward-save", gate)) {
            var players = new PlayerStorage(owner);
            var sessions = new PlayerSessionRegistry(owner);
            var assembler = new PlayerCharacterAssembler(gate);
            var player = new GameplayTestSession(3, map());
            owner.run(() -> { players.add(player.character); sessions.claim(3, player); });
            var fail = new AtomicBoolean(true);
            var stored = new AtomicReference<PlayerCharacterRecord>();
            PlayerCharacterSnapshotRepository repository = (character, items, quests, progress, skills) -> {
                if (fail.get()) throw new IllegalStateException("isolated storage failure");
                stored.set(character);
            };
            try (var saves = new CharacterSaveQueue(repository, assembler, players)) {
                var service = new RewardDeliveryService(players, sessions,
                        new DefaultRewardSystem(gate, GameDataProvider.fixed(Map.of(2000000, new ItemData(2000000)), Map.of())), saves);
                assertThat(service.grant(grant(3)).join().status()).isEqualTo(Status.PERSISTENCE_PENDING);
                fail.set(false);
                assertThat(service.grant(grant(3)).join().status()).isEqualTo(Status.ALREADY_APPLIED);
                owner.run(() -> assertThat(player.character.getMeso()).isEqualTo(20));
                var restored = assembler.fromData(stored.get());
                assertThat(restored.getExp()).isEqualTo(30);
                assertThat(restored.rewardReceipts()).containsEntry("batch-1", grant(3).fingerprint());
            }
        }
    }

    @Test public void corruptReceiptCannotSilentlyEraseIdempotencyHistory() {
        assertThatThrownBy(() -> RewardReceiptCodec.decode("v1\nbatch=broken\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MapleMap map() { var result = new MapleMap(); result.setMapId(100); return result; }

    private static RewardGrant grant(long id) { return new RewardGrant("batch-1", id, Map.of(2000000, 2), 20, 30); }
}
