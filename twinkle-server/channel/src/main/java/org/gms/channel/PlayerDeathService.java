package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.logic.ProgressionSystem;
import org.gms.i18n.I18n;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;

import java.time.Clock;

/**
 * 玩家死亡后的统一清理：取消仍在生效的 Buff、收起椅子，并把变更同步给同图玩家。
 *
 * <p>死亡状态本身由 HP 归零表达，不额外维护 dead 标志；{@link GameplaySession#canAct}
 * 以 HP &gt; 0 为行动门槛。复活统一走 {@link MapTransitionService#revive}，由
 * {@link ChangeMapHandler} 在 HP &lt;= 0 时触发。
 *
 * <p>本类只处理稳定层状态与协议投影，不持有跨操作状态；每个频道装配一份。
 */
@Log4j2
public final class PlayerDeathService {

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
        if (character.getMapObject() != null) {
            log.info(I18n.message("log.combat.player_dead"),
                    character.getName(), character.getMapObject().getMapId());
        }
    }
}
