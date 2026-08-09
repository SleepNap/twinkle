package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.domain.game.Character;
import org.gms.domain.game.lease.ControllerLeaseService;
import org.gms.domain.game.lease.LeaseOwner;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.Arrays;

/**
 * 怪物移动处理（RecvOpcode.MOVE_LIFE 0xBC，事故报告阶段 B 的续租入口）。
 *
 * <p>职责：解析 v83 怪物移动包 → 调 {@link ControllerLeaseService#renew} 完成
 * <b>已验证续租</b>（fail-closed：任一校验不过即丢弃整包，不广播不续租，防伪造/迟到/
 * 越权 MOVE_LIFE 养活坏控制者）→ 成功后把移动广播给地图其他玩家 + 回 {@code
 * MOVE_MONSTER_RESPONSE} 给控制者。
 *
 * <p>v83 收包布局（思路参考自 BeiDou-Server 的 MoveMonsterHandler，实现自研，待 parity
 * 录包确认）：{@code oid(4) + moveId(4) + startX(2) + startY(2) + skill(1) +
 * [skill!=0: skillLevel(1) + skillId(4)] + movement[]}。本 handler 只在 IN_GAME 生效。
 */
public final class MoveLifeHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(MoveLifeHandler.class);

    private final ControllerLeaseService leaseService;
    private final PlayerSessionRegistry sessions;

    public MoveLifeHandler(ControllerLeaseService leaseService, PlayerSessionRegistry sessions) {
        this.leaseService = leaseService;
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到怪物移动包");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到怪物移动包");
            return;
        }
        MapleMap map = chr.getMapObject();
        if (map == null) {
            return;
        }
        Long gen = session.getAttr("sessionGeneration");
        if (gen == null) {
            return;
        }
        LeaseOwner owner = new LeaseOwner(chr.getId(), session.sessionId(), gen);

        int oid = packet.readInt();
        int moveId = packet.readInt();
        packet.readShort();                         // startX（本阶段不用于服务端物理）
        packet.readShort();                         // startY
        int skill = packet.readByte();
        int skillLevel = 0;
        int skillId = 0;
        if (skill != 0) {
            skillLevel = packet.readByte();
            skillId = packet.readInt();
        }
        byte[] movement = packet.readRemaining();

        MapleMonster monster = map.getMonster(oid);
        if (monster == null || !monster.isAlive()) {
            return;                                 // 怪不在了：不续租不广播
        }

        // 已验证续租（fail-closed）：归属校验不过 → 丢弃整包
        if (!leaseService.renew(map.getMapId(), oid, owner)) {
            LOG.debug("MOVE_LIFE 续租被拒（非控制者/旧代际），角色 {} 丢弃怪物 {} 的移动包",
                    chr.getId(), oid);
            return;
        }

        // 广播给地图其他玩家（移动不回显给控制者自己）
        sessions.broadcastToMap(map, GamePacketFactory.moveMonster(oid, movement), chr.getId());
        // 回 MOVE_MONSTER_RESPONSE 给控制者（确认续租）
        session.send(GamePacketFactory.moveMonsterResponse(oid, (short) moveId, skill != 0, skillId));
    }
}
