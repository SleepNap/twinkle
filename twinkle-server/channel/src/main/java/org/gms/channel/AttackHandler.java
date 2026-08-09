package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.domain.game.Character;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.replaceable.CombatSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 攻击处理（近战 CLOSE_RANGE_ATTACK / 远程 RANGED_ATTACK / 魔法 MAGIC_ATTACK 共用）。
 *
 * <p>v83 收包布局（思路参考自 BeiDou-Server 的 AbstractDealDamageHandler，实现自研）：
 * {@code readByte(跳过)} + {@code readByte(高4位=目标数,低4位=每目标伤害行数)} +
 * {@code readInt(skillId)} + [条件 charge 4B：仅特定技能] + {@code skip(8)(动画块)} +
 * {@code readByte(display)} + {@code readByte(direction)} + {@code readByte(stance)} +
 * [远程：readByte+readByte(speed)+readByte+readByte(rangedirection)+skip(7)+[条件 skip(4)]；
 *  近战/魔法：readByte+readByte(speed)+skip(4)] +
 * 每目标 {@code readInt(oid)} + {@code skip(14)} + 每行 {@code readInt(damage)} + [条件 skip(4)]。
 *
 * <p>伤害经 {@link CombatSystem}（版本门 + v83 物理公式 + 目标扣血）。魔法当前走物理
 * 公式（DamageCalculator 只含物理），魔法公式待扩展。handler 只做"收包→调 system→发包"。
 */
public final class AttackHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(AttackHandler.class);

    /** 带 charge 字段的技能集（v83 值，思路参考自 BeiDou；命中即多读 4 字节）。 */
    private static final Set<Integer> CHARGE_SKILLS = Set.of(
            2211006,    // 冰龙 / Evan.ICE_BREATH
            2211007,    // 火龙 / Evan.FIRE_BREATH
            2121001,    // 大爆炸（火毒）
            2221001,    // 大爆炸（冰雷）
            2321001,    // 大爆炸（主教）
            5221003,    // 手榴弹 / Gunslinger.GRENADE
            5101005,    // 开瓶器打击 / Brawler.CORKSCREW_BLOW
            15101004,   // 开瓶器打击 / ThunderBreaker
            14110006    // 毒液炸弹 / NightWalker.POISON_BOMB
    );

    /** 带额外尾段（skill==POISON_BOMB 时）或每目标 skip(4) 的条件技能。 */
    private static final int POISON_BOMB = 14110006;

    private final CombatSystem combatSystem;
    private final PlayerSessionRegistry sessions;
    private final org.gms.domain.game.lease.ControllerLeaseService leaseService;
    /** 攻击类型（决定 stance 后固定段布局）。 */
    private final boolean ranged;
    private final boolean magic;

    public AttackHandler(CombatSystem combatSystem, PlayerSessionRegistry sessions, boolean ranged, boolean magic) {
        this(combatSystem, sessions, null, ranged, magic);
    }

    public AttackHandler(CombatSystem combatSystem, PlayerSessionRegistry sessions,
                         org.gms.domain.game.lease.ControllerLeaseService leaseService, boolean ranged, boolean magic) {
        this.combatSystem = combatSystem;
        this.sessions = sessions;
        this.leaseService = leaseService;
        this.ranged = ranged;
        this.magic = magic;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到攻击包");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到攻击包");
            return;
        }
        MapleMap map = chr.getMapObject();
        if (map == null) {
            return;
        }

        // 注意：opcode 已由 NetworkSession 读出（分发前消费），这里从负载第一个字节开始
        packet.readByte();                              // 跳过
        int numAttackedAndDamage = packet.readByte();
        int numAttacked = (numAttackedAndDamage >>> 4) & 0xF;
        int numDamage = numAttackedAndDamage & 0xF;
        int skill = packet.readInt();
        if (CHARGE_SKILLS.contains(skill)) {
            packet.readInt();                           // charge
        }
        packet.skip(8);                                 // 动画块
        int display = packet.readByte();
        int direction = packet.readByte();
        int stance = packet.readByte();
        int speed;
        if (ranged) {
            packet.readByte();
            speed = packet.readByte();
            packet.readByte();
            packet.readByte();                          // rangedirection
            packet.skip(7);
            if (skill == 3121004 || skill == 3221001 || skill == 5221004 || skill == 13111002) {
                packet.skip(4);                         // 连射/穿刺类额外段
            }
        } else {
            packet.readByte();
            speed = packet.readByte();
            packet.skip(4);
        }

        // 目标伤害区
        List<Target> targets = new ArrayList<>(numAttacked);
        for (int i = 0; i < numAttacked; i++) {
            int oid = packet.readInt();
            packet.skip(14);
            int[] damages = new int[numDamage];
            for (int j = 0; j < numDamage; j++) {
                damages[j] = packet.readInt();
            }
            if (skill == POISON_BOMB) {
                packet.skip(4);
            }
            targets.add(new Target(oid, damages));
        }
        // POISON_BOMB 收尾：skip(4) + x/y
        if (skill == POISON_BOMB && packet.available() >= 8) {
            packet.skip(4);
            packet.readShort();
            packet.readShort();
        }

        // 经 system 结算 + 广播
        applyAndBroadcast(chr, map, skill, numAttacked, numDamage, display, direction, stance, speed, targets, ranged, magic);
    }

    private void applyAndBroadcast(Character chr, MapleMap map, int skill, int numAttacked, int numDamage,
                                   int display, int direction, int stance, int speed,
                                   List<Target> targets, boolean ranged, boolean magic) {
        int[] targetOids = new int[targets.size()];
        int[][] damages = new int[targets.size()][];
        boolean anyDead = false;
        List<Integer> deadOids = new ArrayList<>();

        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            MapleMonster monster = map.getMonster(t.oid);
            targetOids[i] = t.oid;
            if (monster == null || !monster.isAlive()) {
                damages[i] = new int[]{0};
                continue;
            }
            int watk = CombatSystem.BARE_HAND_WATK;
            int dmg = combatSystem.physicalAttack(chr, monster, watk).damage();
            int[] lines = new int[t.damages.length];
            for (int j = 0; j < t.damages.length; j++) {
                lines[j] = dmg;
            }
            damages[i] = lines;
            if (!monster.isAlive()) {
                anyDead = true;
                deadOids.add(monster.getObjectId());
            }
        }

        // 攻击广播（回显排除自己；0xB9-0xBC 由 opcode 区分）
        SendOpcode op = ranged ? SendOpcode.RANGED_ATTACK : (magic ? SendOpcode.MAGIC_ATTACK : SendOpcode.CLOSE_RANGE_ATTACK);
        OutPacket atk = GamePacketFactory.attack(chr.getId(), op, skill, skillLevel(chr, skill),
                (byte) numAttacked, (byte) numDamage, display, direction, stance, speed,
                targetOids, damages, 0);
        sessions.broadcastToMap(map, atk, chr.getId());

        // 怪物受击广播（含攻击者自己，看到伤害数字）
        for (int i = 0; i < targetOids.length; i++) {
            MapleMonster monster = map.getMonster(targetOids[i]);
            if (monster != null && damages[i].length > 0 && damages[i][0] > 0) {
                sessions.broadcastToMap(map, GamePacketFactory.damageMonster(targetOids[i], damages[i][0]));
            }
        }

        // 死亡：KILL_MONSTER 广播 + 释放控制权（MONSTER_DIED）+ 从地图移除
        if (anyDead) {
            for (int oid : deadOids) {
                sessions.broadcastToMap(map, GamePacketFactory.killMonster(oid));
                if (leaseService != null) {
                    // 稳定状态变更同步解除控制归属（报告 §5.5-3，不依赖可重载 handler 补清理）
                    leaseService.release(map.getMapId(), oid, org.gms.domain.game.lease.LeaseReleaseReason.MONSTER_DIED);
                }
                map.removeMonster(oid);
            }
        }
    }

    /** 技能等级（简化：技能表未落地，非 0 技能暂按 0 级处理，攻击广播不发 skill 段）。 */
    private int skillLevel(Character chr, int skill) {
        return skill == 0 ? 0 : 0;
    }

    private record Target(int oid, int[] damages) {
    }
}
