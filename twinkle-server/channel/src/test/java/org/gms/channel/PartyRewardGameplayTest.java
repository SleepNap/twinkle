package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultCombatSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 队伍击杀经验分摊：同图、等级加权、队伍加成与升级结算。 */
public class PartyRewardGameplayTest {

    @Test public void partyMembersOnSameMapShareExpByLevelWithBonus() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var killer = joined(sessions, 1, map);
        var mate = joined(sessions, 2, map);
        killer.character.setParty(7);
        mate.character.setParty(7);
        killer.character.setLevel(10);
        mate.character.setLevel(20);
        monster(map, 100100, 7, 1, 300);
        var attack = attack(sessions, roster(killer.character, mate.character));

        attack.handle(killer, attackPacket(7));

        assertThat(killer.character.getExp()).isEqualTo(110);
        assertThat(mate.character.getExp()).isEqualTo(220);
        assertThat(expGain(killer)).containsExactly(100, 10);
        assertThat(expGain(mate)).containsExactly(200, 20);
    }

    @Test public void partyMemberOnAnotherMapDoesNotShare() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var killer = joined(sessions, 1, map);
        var mate = joined(sessions, 2, map(100));
        killer.character.setParty(7);
        mate.character.setParty(7);
        killer.character.setLevel(10);
        mate.character.setLevel(20);
        monster(map, 100100, 7, 1, 300);
        // 目录返回了另一地图实例的队员，结算仍需按地图实例复核后排除。
        var attack = attack(sessions, roster(killer.character, mate.character));

        attack.handle(killer, attackPacket(7));

        assertThat(killer.character.getExp()).isEqualTo(300);
        assertThat(mate.character.getExp()).isZero();
        assertThat(mate.sent).isEmpty();
        assertThat(expGain(killer)).containsExactly(300, 0);
    }

    @Test public void soloPartyMemberKeepsFullExpWithoutBonus() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var killer = joined(sessions, 1, map);
        killer.character.setParty(7);
        killer.character.setLevel(10);
        monster(map, 100100, 7, 1, 300);
        var attack = attack(sessions, roster(killer.character));

        attack.handle(killer, attackPacket(7));

        assertThat(killer.character.getExp()).isEqualTo(300);
        assertThat(expGain(killer)).containsExactly(300, 0);
    }

    @Test public void partyBonusCountsTowardLevelUp() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var killer = joined(sessions, 1, map);
        var mate = joined(sessions, 2, map);
        killer.character.setParty(7);
        mate.character.setParty(7);
        monster(map, 100100, 7, 1, 30);
        var attack = attack(sessions, roster(killer.character, mate.character));

        attack.handle(killer, attackPacket(7));

        // 平分 15 + 10% 队伍加成 = 16，刚好越过 1 级所需的 15 点。
        assertThat(killer.character.getLevel()).isEqualTo(2);
        assertThat(killer.character.getExp()).isEqualTo(1);
        assertThat(mate.character.getLevel()).isEqualTo(2);
        assertThat(mate.character.getExp()).isEqualTo(1);
        assertThat(expGain(killer)).containsExactly(15, 1);
        assertThat(expGain(mate)).containsExactly(15, 1);
    }

    @Test public void expGainPacketIncludesV83TrailingBonusFields() {
        assertThat(parseExpGain(GameplayPackets.expGain(123, 7))).containsExactly(123, 7);
    }

    private static AttackHandler attack(PlayerSessionRegistry sessions, PartyRoster roster) {
        return new AttackHandler(new DefaultCombatSystem(new DefaultVersionGate()), sessions,
                null, null, new KillRewardService(sessions, roster), false, false);
    }

    private static PartyRoster roster(PlayerCharacter... members) {
        return killer -> List.of(members);
    }

    private static GameplayTestSession joined(PlayerSessionRegistry sessions, long id, MapleMap map) {
        var session = new GameplayTestSession(id, map);
        sessions.claim(id, session);
        return session;
    }

    private static MapleMap map(int id) {
        var map = new MapleMap();
        map.setMapId(id);
        return map;
    }

    private static MapleMonster monster(MapleMap map, int mobId, int oid, int hp, int exp) {
        var data = new MobData(mobId);
        data.setMaxHp(hp);
        data.setExp(exp);
        var monster = new MapleMonster(data);
        monster.setObjectId(oid);
        monster.setX(10);
        monster.setY(10);
        map.addMonster(monster);
        return monster;
    }

    private static ByteArrayInPacket attackPacket(int oid) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(0);
        packet.writeByte(0x11);
        packet.writeInt(0);
        packet.skip(8);
        packet.writeBytes(new byte[5]);
        packet.writeInt(0);
        packet.writeInt(oid);
        packet.skip(14);
        packet.writeInt(1);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static int[] expGain(GameplayTestSession session) {
        OutPacket packet = session.sent.stream()
                .filter(sent -> ((sent.getBytes()[0] & 0xFF) | ((sent.getBytes()[1] & 0xFF) << 8))
                        == SendOpcode.SHOW_STATUS_INFO.getValue())
                .findFirst().orElseThrow();
        return parseExpGain(packet);
    }

    /** 按 v83 SHOW_STATUS_INFO 3 的完整布局读取基础经验和队伍加成，并校验尾部固定段。 */
    private static int[] parseExpGain(OutPacket packet) {
        var wire = new ByteArrayInPacket(packet.getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_STATUS_INFO.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 3);
        assertThat(wire.readByte()).isOne();
        int gain = wire.readInt();
        assertThat(wire.readByte()).isOne();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readByte()).isZero();
        int bonus = wire.readInt();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.available()).isZero();
        return new int[]{gain, bonus};
    }
}
