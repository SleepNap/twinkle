package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.skill.ActiveBuff;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultCombatSystem;
import org.gms.logic.game.DefaultProgressionSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 受伤、死亡和复活封包；伤害只按服务端校验后的结果扣血。 */
public class CombatGameplayTest {

    @Test public void monsterDamageUpdatesHpAndBroadcastsExactPacket() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var player = joined(sessions, 1, map);
        var peer = joined(sessions, 2, map);
        monster(map, 100100, 7, 10, 10);
        var handler = handler(sessions);

        handler.handle(player, damage(0, 20, 100100, 7, 1));

        assertThat(player.character.getHp()).isEqualTo(30);
        assertThat(peer.sent).hasSize(1);
        var wire = new ByteArrayInPacket(peer.sent.getFirst().getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.DAMAGE_PLAYER.getValue());
        assertThat(wire.readInt()).isEqualTo(1);
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readInt()).isEqualTo(20);
        assertThat(wire.readInt()).isEqualTo(100100);
        assertThat(wire.readByte()).isOne();
        assertThat(wire.readShort()).isZero();
        assertThat(wire.readInt()).isEqualTo(20);
        assertThat(wire.available()).isZero();

        var stat = new ByteArrayInPacket(player.sent.getFirst().getBytes());
        assertThat(stat.readUnsignedShort()).isEqualTo(SendOpcode.STAT_CHANGED.getValue());
        assertThat(stat.readByte()).isOne();
        assertThat(stat.readInt()).isEqualTo(GameplayPackets.HP);
        assertThat(stat.readShort()).isEqualTo((short) 30);
    }

    @Test public void lethalDamageClearsBuffsAndChairThenRevivesAtReturnMap() {
        var sessions = new PlayerSessionRegistry();
        var origin = map(100);
        origin.setReturnMapId(300);
        var target = map(300);
        target.putPortal(portal(0, "sp", 10, 20));
        var player = joined(sessions, 1, origin);
        var peer = joined(sessions, 2, origin);
        monster(origin, 100100, 7, 10, 10);
        player.character.setHp(10);
        player.character.putBuff(new ActiveBuff(1L << 33, 2, 1001003, Long.MAX_VALUE));
        player.character.setChairItemId(4030000);
        var handler = handler(sessions);

        handler.handle(player, damage(0, 50, 100100, 7, 1));

        assertThat(player.character.getHp()).isZero();
        assertThat(player.character.buffs()).isEmpty();
        assertThat(player.character.getChairItemId()).isZero();
        assertThat(player.sent).hasSize(3);
        var cancelBuff = new ByteArrayInPacket(player.sent.get(1).getBytes());
        assertThat(cancelBuff.readUnsignedShort()).isEqualTo(SendOpcode.CANCEL_BUFF.getValue());
        assertThat(cancelBuff.readLong()).isZero();
        assertThat(cancelBuff.readLong()).isEqualTo(1L << 33);
        assertThat(cancelBuff.readByte()).isOne();
        var cancelChair = new ByteArrayInPacket(player.sent.get(2).getBytes());
        assertThat(cancelChair.readUnsignedShort()).isEqualTo(SendOpcode.CANCEL_CHAIR.getValue());
        assertThat(cancelChair.readByte()).isZero();

        assertThat(peer.sent).hasSize(3);
        var foreignBuff = new ByteArrayInPacket(peer.sent.get(1).getBytes());
        assertThat(foreignBuff.readUnsignedShort()).isEqualTo(SendOpcode.CANCEL_FOREIGN_BUFF.getValue());
        assertThat(foreignBuff.readInt()).isOne();
        assertThat(foreignBuff.readLong()).isZero();
        assertThat(foreignBuff.readLong()).isEqualTo(1L << 33);
        var foreignChair = new ByteArrayInPacket(peer.sent.get(2).getBytes());
        assertThat(foreignChair.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_CHAIR.getValue());
        assertThat(foreignChair.readInt()).isOne();
        assertThat(foreignChair.readInt()).isZero();

        var transitions = new MapTransitionService(Map.of(300, target)::get, null, null, 1, sessions);
        var change = new ByteArrayOutPacket();
        change.writeByte(0);
        change.writeInt(300);
        change.writeString("sp");
        new ChangeMapHandler(transitions, false).handle(player, new ByteArrayInPacket(change.getBytes()));

        assertThat(player.character.getHp()).isEqualTo(50);
        assertThat(player.character.getMap()).isEqualTo(300);
        assertThat(origin.characters()).doesNotContain(player.character);
        assertThat(target.characters()).containsExactly(player.character);
        new PlayerMapTransitionHandler().handle(player, new ByteArrayInPacket(new byte[0]));
        sessions.visibility().enter(player);
        assertThat(GameplaySession.canAct(player, player.character)).isTrue();
    }

    @Test public void invalidMonsterDamageNeverChangesHp() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var player = joined(sessions, 1, map);
        var peer = joined(sessions, 2, map);
        var monster = monster(map, 100100, 7, 10, 10);
        var handler = handler(sessions);

        handler.handle(player, damage(0, 20, 999999, 7, 0));
        assertThat(player.character.getHp()).isEqualTo(50);

        monster.setAlive(false);
        handler.handle(player, damage(0, 20, 100100, 7, 0));
        assertThat(player.character.getHp()).isEqualTo(50);

        monster.setAlive(true);
        monster.setX(5_000);
        handler.handle(player, damage(0, 20, 100100, 7, 0));
        assertThat(player.character.getHp()).isEqualTo(50);

        monster.setX(10);
        handler.handle(player, damage(0, -5, 100100, 7, 0));
        handler.handle(player, damage(0, 20, -1, 7, 0));
        assertThat(player.character.getHp()).isEqualTo(50);
        assertThat(peer.sent).isEmpty();
        assertThat(player.sent).isEmpty();
    }

    @Test public void malformedAndDuplicateAttackTargetsAreRejected() {
        var sessions = new PlayerSessionRegistry();
        var map = map(100);
        var player = joined(sessions, 1, map);
        var peer = joined(sessions, 2, map);
        var monster = monster(map, 100100, 7, 10, 10);
        var attack = new AttackHandler(new DefaultCombatSystem(new DefaultVersionGate()), sessions, false, false);

        attack.handle(player, attackPacket(0x21, 1, 7));
        assertThat(monster.getHp()).isEqualTo(100);

        attack.handle(player, attackPacket(0x21, 1, 7, 7));
        assertThat(monster.getHp()).isEqualTo(100);

        attack.handle(player, attackPacket(0x12, 2, 7));
        assertThat(monster.getHp()).isEqualTo(99);

        var wire = new ByteArrayInPacket(peer.sent.getFirst().getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.CLOSE_RANGE_ATTACK.getValue());
        assertThat(wire.readInt()).isOne();
        assertThat(wire.readByte()).isEqualTo((byte) 0x12);
        assertThat(wire.readByte()).isEqualTo((byte) 0x5B);
        assertThat(wire.readByte()).isZero();       // skill level
        assertThat(wire.readByte()).isZero();       // display
        assertThat(wire.readByte()).isZero();       // direction
        assertThat(wire.readByte()).isZero();       // stance
        assertThat(wire.readByte()).isZero();       // speed
        assertThat(wire.readByte()).isEqualTo((byte) 0x0A);
        assertThat(wire.readInt()).isZero();        // projectile
        assertThat(wire.readInt()).isEqualTo(7);
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readInt()).isOne();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.available()).isZero();
    }

    private static TakeDamageHandler handler(PlayerSessionRegistry sessions) {
        return new TakeDamageHandler(sessions, new PlayerDeathService(
                new DefaultProgressionSystem(new DefaultVersionGate()), sessions, Clock.systemUTC()));
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

    private static MapleMonster monster(MapleMap map, int mobId, int oid, int x, int y) {
        var data = new MobData(mobId);
        data.setMaxHp(100);
        var monster = new MapleMonster(data);
        monster.setObjectId(oid);
        monster.setX(x);
        monster.setY(y);
        map.addMonster(monster);
        return monster;
    }

    private static Portal portal(int id, String name, int x, int y) {
        var portal = new Portal(id, name, PortalType.MOVE, x, y);
        portal.setId(id);
        portal.setName(name);
        portal.setX(x);
        portal.setY(y);
        return portal;
    }

    private static ByteArrayInPacket damage(int from, int damage, int mobId, int oid, int direction) {
        var packet = new ByteArrayOutPacket();
        packet.writeInt(0);
        packet.writeByte(from);
        packet.writeByte(0);
        packet.writeInt(damage);
        packet.writeInt(mobId);
        packet.writeInt(oid);
        packet.writeByte(direction);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static ByteArrayInPacket attackPacket(int count, int lines, int... oids) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(0);
        packet.writeByte(count);
        packet.writeInt(0);
        packet.skip(8);
        packet.writeBytes(new byte[5]);
        packet.writeInt(0);
        for (int oid : oids) {
            packet.writeInt(oid);
            packet.skip(14);
            for (int i = 0; i < lines; i++) {
                packet.writeInt(9999);
            }
        }
        return new ByteArrayInPacket(packet.getBytes());
    }
}
