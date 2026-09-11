package org.gms.channel;

import org.gms.domain.game.skill.SkillDefinition;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.v83.V83FileTime;
import org.gms.net.packet.v83.V83SkillPoints;
import org.gms.domain.game.logic.ProgressionSystem;
import org.gms.wz.WzNode;
import org.gms.wz.WzResources;
import org.gms.wz.WzResourceRegistry;

import java.util.HashMap;
import java.util.Map;

/** AP/SP 请求适配；WZ 技能上限和前置条件缺失时拒绝，不能由客户端自报技能等级。 */
public final class ProgressionHandler implements PacketHandler {
    private final ProgressionSystem system;
    private final WzResourceRegistry resources;
    private final boolean skillPoints;

    public ProgressionHandler(ProgressionSystem system, WzResourceRegistry resources, boolean skillPoints) {
        this.system = system;
        this.resources = resources;
        this.skillPoints = skillPoints;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        try {
            var character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 8) return;
            packet.skip(4);
            int value = packet.readInt();
            if (!skillPoints) {
                if (!system.allocateAp(character, Map.of(value, 1))) return;
                int stat = switch (value) { case 0x40 -> character.getStrStat(); case 0x80 -> character.getDexStat();
                    case 0x100 -> character.getIntStat(); default -> character.getLukStat(); };
                session.send(GameplayPackets.stats(Map.of(value, stat, GameplayPackets.AP, character.getAp())));
            } else if (system.allocateSp(character, definition(value))) {
                var learned = character.getSkill(value);
                var response = GameplayPackets.packet(SendOpcode.UPDATE_SKILLS);
                response.writeByte(1);
                response.writeShort(1);
                response.writeInt(value);
                response.writeInt(learned.level());
                response.writeInt(learned.masterLevel());
                response.writeLong(V83FileTime.encode(learned.expiration()));
                response.writeByte(4);
                session.send(response);
                var points = GameplayPackets.packet(SendOpcode.STAT_CHANGED);
                points.writeBool(true);
                points.writeInt(GameplayPackets.SP);
                V83SkillPoints.write(points, character.getJob(), character.getSp());
                session.send(points);
            }
        } finally { session.send(GameplayPackets.enableActions()); }
    }

    public SkillDefinition definition(int skillId) {
        if (skillId <= 0) return null;
        WzNode skill = resources.resource(WzResources.SKILLS).get(skillId / 10000 + ".img.xml")
                .flatMap(root -> root.child("skill")).flatMap(root -> root.child(String.format("%07d", skillId))).orElse(null);
        if (skill == null) return null;
        int maximum = skill.child("level").map(levels -> levels.children().keySet().stream()
                .filter(key -> key.matches("[0-9]+" )).mapToInt(Integer::parseInt).max().orElse(0)).orElse(0);
        Map<Integer, Integer> requirements = new HashMap<>();
        skill.child("req").ifPresent(req -> req.values().forEach((key, value) -> {
            if (key.matches("[0-9]+")) requirements.put(Integer.parseInt(key), Integer.parseInt(value));
        }));
        return new SkillDefinition(skillId, maximum, skill.getInt("reqLevel").orElse(0), requirements);
    }
}
