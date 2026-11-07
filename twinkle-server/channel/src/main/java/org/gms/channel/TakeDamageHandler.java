package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

import java.util.Map;

/**
 * 玩家受到伤害（RecvOpcode.TAKE_DAMAGE 0x30）。
 *
 * <p>v83 收包布局（字段事实核对北斗 TakeDamageHandler / PacketCreator.damagePlayer）：
 * {@code int(保留)} + {@code byte(damageFrom)} + {@code byte(element)} + {@code int(damage)}；
 * 当 damageFrom 不是 -3/-4 时再读 {@code int(monsterIdFrom)} + {@code int(oid)} +
 * {@code byte(direction)}，之后是可选的反射/防御段。
 *
 * <p>当前只接受 {@code damageFrom >= 0} 的怪物攻击：服务端必须按 oid 找到同图存活怪物，
 * 校验模板 ID 与距离后再扣血。环境伤害（负值）、反射/防御段和异常状态留待后续批次；
 * 未通过校验的包直接丢弃，不修改 HP，避免客户端伪造远程或已死亡怪物的伤害。
 */
public final class TakeDamageHandler implements PacketHandler {

    /** 单次伤害上限：v83 怪物攻击远低于此值，仅用于拒绝溢出/异常字段。 */
    private static final int MAX_DAMAGE = 1_000_000;

    /** 怪物攻击的有效距离；远于该距离的 oid 不接受，防止全图伪造。 */
    private static final int MAX_RANGE = 800;

    private final PlayerSessionRegistry sessions;
    private final PlayerDeathService deaths;
    private final PartyHpNotifier parties;

    public TakeDamageHandler(PlayerSessionRegistry sessions, PlayerDeathService deaths) {
        this(sessions, deaths, null);
    }

    public TakeDamageHandler(PlayerSessionRegistry sessions, PlayerDeathService deaths, PartyHpNotifier parties) {
        this.sessions = sessions;
        this.deaths = deaths;
        this.parties = parties;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || !GameplaySession.canAct(session, character)
                || sessions.get(character.getId()) != session) {
            return;
        }
        if (packet.available() < 10) {
            return;
        }
        packet.readInt();                          // v83 保留字段
        int damageFrom = packet.readByte();        // 怪物攻击序号；负值为环境/特殊伤害
        packet.readByte();                         // element
        int damage = packet.readInt();
        if (damageFrom < 0 || damage < 0 || damage > MAX_DAMAGE || packet.available() < 9) {
            return;
        }
        int monsterIdFrom = packet.readInt();
        int oid = packet.readInt();
        int direction = packet.readByte();

        MapleMap map = character.getMapObject();
        MapleMonster monster = map == null ? null : map.getMonster(oid);
        if (monster == null || !monster.isAlive() || monster.getData() == null
                || monster.getData().getMobId() != monsterIdFrom
                || !GameplaySession.near(character, monster.getX(), monster.getY(), MAX_RANGE)) {
            return;
        }

        int hp = character.getHp();
        int newHp = Math.max(0, hp - damage);
        character.setHp(newHp);
        if (parties != null) {
            parties.updateHp(character);
        }

        if (damage > 0) {
            // 受伤回显排除本人；本人只收 HP 状态纠正，避免同一伤害被客户端计算两次。
            sessions.broadcastToMap(map, GamePacketFactory.damagePlayer(
                    damageFrom, monsterIdFrom, character.getId(), damage, direction), character.getId());
        }
        session.send(GameplayPackets.stats(Map.of(GameplayPackets.HP, newHp)));
        if (newHp == 0) {
            deaths.onDeath(session, character);
        } else {
            session.send(GameplayPackets.enableActions());
        }
    }
}
