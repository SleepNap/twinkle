package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.logic.ProgressionSystem;
import org.gms.i18n.I18n;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;

import java.time.Clock;
import java.util.Map;

/**
 * 玩家死亡后的统一清理：取消仍在生效的 Buff、收起椅子，并按 v83 规则结算死亡经验损失。
 *
 * <p>死亡状态本身由 HP 归零表达，不额外维护 dead 标志；{@link GameplaySession#canAct}
 * 以 HP &gt; 0 为行动门槛。复活统一走 {@link MapTransitionService#revive}，由
 * {@link ChangeMapHandler} 在 HP &lt;= 0 时触发。
 *
 * <p>经验损失：新手不扣；安全护符优先消耗并免扣；地图禁止扣经验字段生效时免扣；
 * 城镇扣当前等级需求的 1%，野外 LUK &lt; 50 扣 10%、LUK ≥ 50 扣 5%，最多扣到 0。
 * 复活护符/活动免扣和武陵道场例外尚未接入，留待后续批次。
 *
 * <p>本类只处理稳定层状态与协议投影，不持有跨操作状态；每个频道装配一份。
 */
@Log4j2
public final class PlayerDeathService {

    /** v83 安全护符（现金栏）；消耗 1 个后本次死亡不扣经验。 */
    private static final int SAFETY_CHARM_ITEM_ID = 5_130_000;

    /** Map.wz fieldLimit 的 NO_EXP_DECREASE 位（v83 0x80000）。 */
    private static final int FIELD_LIMIT_NO_EXP_DECREASE = 0x80_000;

    private final ProgressionSystem progression;
    private final PlayerSessionRegistry sessions;
    private final Clock clock;

    public PlayerDeathService(ProgressionSystem progression, PlayerSessionRegistry sessions, Clock clock) {
        this.progression = progression;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * HP 已归零后调用：移除全部生效 Buff、收起椅子，并记录死亡日志。
     *
     * <p>重复调用是幂等的：Buff 已清空、椅子已归零时不再发取消包。
     */
    public void onDeath(PacketSession session, PlayerCharacter character) {
        long mask = progression.cancelBuff(character, 0, clock.millis(), true);
        if (mask != 0) {
            OutPacket cancellation = GameplayPackets.packet(SendOpcode.CANCEL_BUFF);
            cancellation.writeLong(0).writeLong(mask).writeByte(1);
            session.send(cancellation);
            if (character.getMapObject() != null) {
                sessions.broadcastToMap(character.getMapObject(),
                        PlayerPresencePackets.cancelBuff(character.getId(), mask), character.getId());
            }
        }
        if (character.getChairItemId() != 0) {
            character.setChairItemId(0);
            session.send(PlayerPresencePackets.cancelChair());
            if (character.getMapObject() != null) {
                sessions.broadcastToMap(character.getMapObject(),
                        PlayerPresencePackets.chair(character.getId(), 0), character.getId());
            }
        }
        character.setStance(0);
        applyDeathPenalty(session, character);
        if (character.getMapObject() != null) {
            log.info(I18n.message("log.combat.player_dead"),
                    character.getName(), character.getMapObject().getMapId());
        }
    }

    /** v83 死亡经验损失；只更新 EXP，不播放白色经验提示，与服务端状态纠正一致。 */
    private void applyDeathPenalty(PacketSession session, PlayerCharacter character) {
        if (character.getJob() == 0) return;
        if (consumeSafetyCharm(session, character)) return;
        boolean town = character.getMapObject() != null && character.getMapObject().isTown();
        if (character.getMapObject() != null
                && (character.getMapObject().getFieldLimit() & FIELD_LIMIT_NO_EXP_DECREASE) != 0) return;
        int required = V83ExpTable.requiredForLevel(character.getLevel());
        if (required <= 0) return;
        long penalty = town ? required / 100L
                : character.totalLuk() < 50 ? required / 10L : required / 20L;
        long loss = Math.min(character.getExp(), penalty);
        if (loss <= 0) return;
        character.setExp(character.getExp() - loss);
        session.send(GameplayPackets.stats(Map.of(GameplayPackets.EXP,
                (int) Math.min(Integer.MAX_VALUE, character.getExp()))));
    }

    /** 优先消耗一个安全护符并推送现金栏变更；没有护符或扣除失败时返回 false。 */
    private boolean consumeSafetyCharm(PacketSession session, PlayerCharacter character) {
        if (character.getItemCount(SAFETY_CHARM_ITEM_ID) <= 0) return false;
        var before = GameplayPackets.inventory(character, InventoryType.CASH);
        if (!character.removeItem(SAFETY_CHARM_ITEM_ID, 1)) return false;
        GameplayPackets.inventoryChanges(InventoryType.CASH, before,
                GameplayPackets.inventory(character, InventoryType.CASH)).forEach(session::send);
        return true;
    }
}
