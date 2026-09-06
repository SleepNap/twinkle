package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.skill.BuffDefinition;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.ProgressionSystem;
import org.gms.wz.WzNode;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.WzResources;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 首批自身增益：圣甲术、魔法盾甲、集中术；未实现的技能不扣蓝、不假报施放成功。 */
public final class ActiveSkillHandler implements PacketHandler {
    private static final Set<Integer> SUPPORTED = Set.of(1001003, 2001003, 3001003);
    private static final Map<String, Long> ATTRIBUTES = Map.of("pdd", 1L << 33, "acc", 1L << 36, "eva", 1L << 37);
    private final WzResourceRegistry resources;
    private final ProgressionSystem system;
    private final Clock clock;
    private final PlayerSessionRegistry sessions;

    public ActiveSkillHandler(WzResourceRegistry resources, ProgressionSystem system, Clock clock) {
        this(resources, system, clock, null);
    }
    public ActiveSkillHandler(WzResourceRegistry resources, ProgressionSystem system, Clock clock, PlayerSessionRegistry sessions) {
        this.resources = resources; this.system = system; this.clock = clock;
        this.sessions = sessions;
    }
    @Override public void handle(PacketSession session, InPacket packet) {
        try {
            PlayerCharacter character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || !current(session, character) || packet.available() < 9) return;
            packet.skip(4);
            int skillId = packet.readInt(), requestedLevel = packet.readByte() & 255;
            var skill = character.getSkill(skillId);
            if (skill == null || skill.level() != requestedLevel) return;
            BuffDefinition effect = definition(skillId, skill.level());
            if (!system.castBuff(character, effect, clock.millis())) return;
            var reply = GameplayPackets.packet(SendOpcode.GIVE_BUFF);
            reply.writeLong(0);
            reply.writeLong(effect.stats().keySet().stream().reduce(0L, (mask, value) -> mask | value));
            new TreeMap<>(effect.stats()).forEach((mask, value) -> {
                reply.writeShort(value); reply.writeInt(skillId); reply.writeInt(effect.durationMillis());
            });
            reply.writeInt(0); reply.writeByte(0); reply.writeInt(new TreeMap<>(effect.stats()).firstEntry().getValue());
            session.send(reply);
            if (sessions != null) {
                sessions.broadcastToMap(character.getMapObject(), PlayerPresencePackets.skillEffect(character.getId(),
                        skillId, skill.level(), character.getStance() & 1), character.getId());
                sessions.broadcastToMap(character.getMapObject(), PlayerPresencePackets.buff(character.getId(), effect.stats()),
                        character.getId());
            }
            session.send(GameplayPackets.stats(Map.of(GameplayPackets.HP, character.getHp(), GameplayPackets.MP, character.getMp())));
        } finally { session.send(GameplayPackets.enableActions()); }
    }
    public BuffDefinition definition(int skill, int level) {
        if (!SUPPORTED.contains(skill)) return null;
        WzNode node = resources.resource(WzResources.SKILLS).get(skill / 10000 + ".img.xml")
                .flatMap(root -> root.child("skill")).flatMap(root -> root.child(Integer.toString(skill)))
                .flatMap(root -> root.child("level")).flatMap(root -> root.child(Integer.toString(level))).orElse(null);
        if (node == null || !node.children().isEmpty() || !Set.of("hs", "time", "mpCon", "hpCon", "cooltime", "pdd", "acc", "eva")
                .containsAll(node.values().keySet())) return null;
        int duration = node.getInt("time").orElse(0), cooldown = node.getInt("cooltime").orElse(0);
        if (duration <= 0 || duration > 86_400 || cooldown < 0 || cooldown > 86_400) return null;
        Map<Long, Integer> stats = new TreeMap<>();
        ATTRIBUTES.forEach((key, mask) -> { if (node.getInt(key).isPresent()) stats.put(mask, node.getInt(key).getAsInt()); });
        return new BuffDefinition(skill, level, node.getInt("hpCon").orElse(0), node.getInt("mpCon").orElse(0),
                duration * 1000, cooldown * 1000, stats);
    }
    public void cancel(PacketSession session, InPacket packet) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || !current(session, character) || packet.available() < 4) return;
        sendCancellation(session, system.cancelBuff(character, packet.readInt(), clock.millis(), false));
        session.send(GameplayPackets.enableActions());
    }
    public void expire(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character != null && current(session, character)) {
            sendCancellation(session, system.cancelBuff(character, 0, clock.millis(), true));
        }
    }
    private boolean current(PacketSession session, PlayerCharacter character) {
        return sessions == null || sessions.get(character.getId()) == session;
    }
    private void sendCancellation(PacketSession session, long mask) {
        if (mask == 0) return;
        var packet = GameplayPackets.packet(SendOpcode.CANCEL_BUFF);
        packet.writeLong(0); packet.writeLong(mask); packet.writeByte(1);
        session.send(packet);
        PlayerCharacter character = GameplaySession.character(session);
        if (sessions != null && character != null && character.getMapObject() != null) {
            sessions.broadcastToMap(character.getMapObject(), PlayerPresencePackets.cancelBuff(character.getId(), mask),
                    character.getId());
        }
    }
}
