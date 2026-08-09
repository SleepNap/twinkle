package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.Character;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 玩家地图转移完成处理（RecvOpcode.PLAYER_MAP_TRANSFER）。
 *
 * <p>客户端收到 getCharInfo 并加载完成后发本包。最小切片仅标记阶段完成
 * （怪物 controller 重分配等留 M2-2 战斗机制）。
 */
@Log4j2
public final class PlayerMapTransitionHandler implements PacketHandler {


    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到地图转移完成包");
            return;
        }
        Character chr = session.getAttr("character");
        log.info("进图完成: {} (id={})", chr.getName(), chr.getId());
    }
}
