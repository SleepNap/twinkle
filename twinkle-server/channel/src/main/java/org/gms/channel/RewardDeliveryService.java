package org.gms.channel;

import java.util.EnumMap;
import org.gms.net.packet.v83.V83ItemSnapshot;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.net.packet.PacketSession;
import org.gms.domain.game.logic.RewardSystem;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;
import org.gms.service.admin.RewardResult.Status;
import org.gms.i18n.I18n;
import lombok.extern.log4j.Log4j2;

/** 每条消息只操作一位玩家；游戏状态在所属频道串行，存档在后台，异常不传播到其他消息。 */
@Log4j2
public final class RewardDeliveryService {
    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final RewardSystem rewards;
    private final CharacterSaveQueue saves;

    public RewardDeliveryService(PlayerStorage players, PlayerSessionRegistry sessions,
                                 RewardSystem rewards, CharacterSaveQueue saves) {
        this.players = players; this.sessions = sessions; this.rewards = rewards; this.saves = saves;
    }

    public CompletableFuture<RewardResult> grant(RewardGrant grant) {
        return players.execution().submit(() -> apply(grant)).thenCompose(result -> result)
                .exceptionally(error -> {
                    log.error(I18n.message("log.reward.failed"), grant.characterId(), error);
                    return RewardResult.of(grant, Status.FAILED);
                });
    }

    private CompletableFuture<RewardResult> apply(RewardGrant grant) {
        var character = players.getById(grant.characterId());
        if (character == null) return done(grant, Status.OFFLINE);
        PacketSession session = sessions.get(grant.characterId());
        if (session == null) return done(grant, Status.OFFLINE);
        if (!GameplaySession.canAct(session, character)) return done(grant, Status.BUSY);
        // 防重检查在读取模板前完成；模板后来删除不能使已成功的请求被重新解释。
        String previous = character.rewardReceipts().get(grant.batchId());
        if (previous != null && !previous.equals(grant.fingerprint())) return done(grant, Status.IDEMPOTENCY_CONFLICT);
        Map<InventoryType, Map<Short, V83ItemSnapshot>> before = new EnumMap<>(InventoryType.class);
        for (InventoryType type : InventoryType.values()) {
            if (type != InventoryType.UNDEFINED) before.put(type, GameplayPackets.inventory(character, type));
        }
        Status status = previous == null ? rewards.grant(character, grant) : Status.ALREADY_APPLIED;
        if (status != Status.APPLIED && status != Status.ALREADY_APPLIED) return done(grant, status);
        // 回执随同角色、物品、经验和金币一次存档；失败重试只会重新保存，绝不再次加资产。
        CompletableFuture<Void> saved = saves.saveAsync(character);
        if (status == Status.APPLIED) {
            try {
                session.send(GameplayPackets.stats(Map.of(GameplayPackets.MESO, character.getMeso(),
                        GameplayPackets.EXP, (int) character.getExp())));
                before.forEach((type, oldItems) -> GameplayPackets.inventoryChanges(type, oldItems,
                        GameplayPackets.inventory(character, type)).forEach(session::send));
            } catch (RuntimeException notifyFailure) {
                log.warn(I18n.message("log.reward.notify_failed"), grant.characterId(), notifyFailure);
                session.close(I18n.message("error.reward.resync"));
            }
        }
        return saved.handle((ignored, failure) -> RewardResult.of(grant,
                failure == null ? status : Status.PERSISTENCE_PENDING));
    }

    private static CompletableFuture<RewardResult> done(RewardGrant grant, Status status) {
        return CompletableFuture.completedFuture(RewardResult.of(grant, status));
    }
}
