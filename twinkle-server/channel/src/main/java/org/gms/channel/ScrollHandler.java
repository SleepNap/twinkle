package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

/**
 * 升级卷轴收包（{@code USE_UPGRADE_SCROLL 0x56}）。
 *
 * <p>v83 字段：4 字节未用 + 卷轴槽位 + 装备槽位 + 2 字节标志（bit 1 表示使用白色卷轴）。
 * 业务校验、扣物品和装备变更由 {@link EquipmentService#scroll} 统一完成。
 */
public final class ScrollHandler implements PacketHandler {
    private final EquipmentService equipment;
    private final PlayerSessionRegistry sessions;

    public ScrollHandler(EquipmentService equipment, PlayerSessionRegistry sessions) {
        this.equipment = equipment;
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        try {
            PlayerCharacter character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 10) return;
            packet.skip(4);
            short scrollSlot = packet.readShort();
            short equipSlot = packet.readShort();
            boolean whiteScroll = (packet.readShort() & 2) != 0;
            if (sessions.get(character.getId()) != session || !GameplaySession.canAct(session, character)) return;
            equipment.scroll(session, scrollSlot, equipSlot, whiteScroll);
        } finally {
            session.send(GameplayPackets.enableActions());
        }
    }
}
