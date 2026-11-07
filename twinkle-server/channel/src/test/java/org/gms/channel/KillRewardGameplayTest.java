package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.SpawnPoint;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultCombatSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 鍑绘潃缁忛獙銆佸崌绾с€丄P/SP 涓庨噸鐢熸帓鏈燂紱涓嶈鐩栨帀钀藉拰闃熶紞鍒嗘垚銆?*/
public class KillRewardGameplayTest {

    @Test public void killAwardsMonsterExpAndSendsGainPacket() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var player = joined(sessions, 1, map);
        monster(map, 100100, 7, 10, 10, 1, 3);
        var attack = attack(sessions);

        attack.handle(player, attackPacket(7));

        assertThat(player.character.getLevel()).isOne();
        assertThat(player.character.getExp()).isEqualTo(3);
        var gain = packet(player, SendOpcode.SHOW_STATUS_INFO);
        var wire = new ByteArrayInPacket(gain.getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_STATUS_INFO.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 3);
        assertThat(wire.readByte()).isOne();
        assertThat(wire.readInt()).isEqualTo(3);
        assertThat(wire.readByte()).isOne();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.available()).isZero();

        var exp = packet(player, SendOpcode.STAT_CHANGED);
        var stat = new ByteArrayInPacket(exp.getBytes());
        assertThat(stat.readUnsignedShort()).isEqualTo(SendOpcode.STAT_CHANGED.getValue());
        assertThat(stat.readByte()).isOne();
        assertThat(stat.readInt()).isEqualTo(GameplayPackets.EXP);
        assertThat(stat.readInt()).isEqualTo(3);
        assertThat(stat.available()).isZero();
    }

    @Test public void enoughExpLevelsUpAndUpdatesApHpMp() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var player = joined(sessions, 1, map);
        player.character.setExp(14);
        monster(map, 100100, 7, 10, 10, 1, 1);
        var attack = attack(sessions);

        attack.handle(player, attackPacket(7));

        assertThat(player.character.getLevel()).isEqualTo(2);
        assertThat(player.character.getExp()).isZero();
        assertThat(player.character.getAp()).isEqualTo(5);
        assertThat(player.character.getMaxHp()).isEqualTo(114);
        assertThat(player.character.getMaxMp()).isEqualTo(16);
        assertThat(player.character.getHp()).isEqualTo(114);
        assertThat(player.character.getMp()).isEqualTo(16);

        var levelUp = packet(player, SendOpcode.STAT_CHANGED);
        var wire = new ByteArrayInPacket(levelUp.getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STAT_CHANGED.getValue());
        assertThat(wire.readByte()).isOne();
        assertThat(wire.readInt()).isEqualTo(0x10 | GameplayPackets.HP | GameplayPackets.MAX_HP
                | GameplayPackets.MP | GameplayPackets.MAX_MP | GameplayPackets.AP | GameplayPackets.EXP);
        assertThat(wire.readByte()).isEqualTo((byte) 2);
        assertThat(wire.readShort()).isEqualTo((short) 114);
        assertThat(wire.readShort()).isEqualTo((short) 114);
        assertThat(wire.readShort()).isEqualTo((short) 16);
        assertThat(wire.readShort()).isEqualTo((short) 16);
        assertThat(wire.readShort()).isEqualTo((short) 5);
        assertThat(wire.readInt()).isZero();
        assertThat(wire.available()).isZero();
    }

    @Test public void jobCharacterGainsSkillPointAndEncodesSpMask() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var player = joined(sessions, 1, map);
        player.character.setJob(100);
        player.character.setExp(14);
        monster(map, 100100, 7, 10, 10, 1, 1);
        var attack = attack(sessions);

        attack.handle(player, attackPacket(7));

        assertThat(player.character.getLevel()).isEqualTo(2);
        assertThat(player.character.getSp().split(",")[0]).isEqualTo("3");
        var levelUp = packet(player, SendOpcode.STAT_CHANGED);
        var wire = new ByteArrayInPacket(levelUp.getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STAT_CHANGED.getValue());
        assertThat(wire.readByte()).isOne();
        assertThat(wire.readInt()).isEqualTo(0x10 | GameplayPackets.HP | GameplayPackets.MAX_HP
                | GameplayPackets.MP | GameplayPackets.MAX_MP | GameplayPackets.AP
                | GameplayPackets.SP | GameplayPackets.EXP);
        assertThat(wire.readByte()).isEqualTo((byte) 2);
        assertThat(wire.readShort()).isEqualTo((short) 126);
        assertThat(wire.readShort()).isEqualTo((short) 126);
        assertThat(wire.readShort()).isEqualTo((short) 10);
        assertThat(wire.readShort()).isEqualTo((short) 10);
        assertThat(wire.readShort()).isEqualTo((short) 5);
        assertThat(wire.readShort()).isEqualTo((short) 3);
        assertThat(wire.readInt()).isZero();
        assertThat(wire.available()).isZero();
    }

    @Test public void expTableHasV83Thresholds() {
        assertThat(V83ExpTable.requiredForLevel(1)).isEqualTo(15);
        assertThat(V83ExpTable.requiredForLevel(2)).isEqualTo(34);
        assertThat(V83ExpTable.requiredForLevel(6)).isEqualTo(372);
        assertThat(V83ExpTable.requiredForLevel(199)).isEqualTo(1608855764);
        assertThat(V83ExpTable.requiredForLevel(200)).isZero();
    }

    @Test public void killedMonsterSchedulesNearestRespawnPoint() {
        var data = new MobData(100100);
        data.setMaxHp(1);
        data.setExp(3);
        var sessions = new PlayerSessionRegistry();
        var service = new MonsterSpawnService(GameDataProvider.fixed(Map.of(), Map.of(100100, data)), sessions, null);
        var map = map(100);
        var spawn = new SpawnPoint(100100, 10, 10);
        spawn.setRespawnInterval(60_000);
        map.addSpawnPoint(spawn);
        var monster = new MapleMonster(data);
        monster.setObjectId(7);
        monster.setX(12);
        monster.setY(12);
        try {
            service.onKilled(map, monster);
            assertThat(service.stats().respawnScheduled()).isOne();
        } finally {
            service.close();
        }
    }

    private static AttackHandler attack(PlayerSessionRegistry sessions) {
        return new AttackHandler(new DefaultCombatSystem(new DefaultVersionGate()), sessions,
                null, null, new KillRewardService(sessions), false, false);
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

    private static MapleMonster monster(MapleMap map, int mobId, int oid, int x, int y, int hp, int exp) {
        var data = new MobData(mobId);
        data.setMaxHp(hp);
        data.setExp(exp);
        var monster = new MapleMonster(data);
        monster.setObjectId(oid);
        monster.setX(x);
        monster.setY(y);
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

    private static OutPacket packet(GameplayTestSession session, SendOpcode opcode) {
        return session.sent.stream().filter(packet -> packet.getBytes().length >= 2
                        && ((packet.getBytes()[0] & 0xFF) | ((packet.getBytes()[1] & 0xFF) << 8)) == opcode.getValue())
                .findFirst().orElseThrow();
    }
}
