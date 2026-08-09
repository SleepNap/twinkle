package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;

/**
 * 频道侧游戏内包构造（v83 字节级兼容红线 1，包布局对齐参考项目，实现自研）。
 *
 * <p>M3-5 覆盖移动/战斗/刷怪的广播包。布局思路参考自 BeiDou-Server 的
 * PacketCreator（movePlayer / spawnMonsterInternal / killMonster / damageMonster），
 * 按 twinkle 简化裁剪自研。
 */
public final class GamePacketFactory {

    private GamePacketFactory() {
    }

    /** 玩家移动广播（0xB9）：cid + int 0 + 原始移动段透传（不含 9 字节收包头）。 */
    public static OutPacket movePlayer(long cid, byte[] movementBytes) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.MOVE_PLAYER.getValue());
        p.writeInt((int) cid);
        p.writeInt(0);
        p.writeBytes(movementBytes);
        return p;
    }

    /** 怪物生成广播（0xEC）：oid + 5 + mobId + 16B 状态占位 + x/y + 姿态 + 0 + 0 + -2 + 队伍 + 0。 */
    public static OutPacket spawnMonster(MapleMonster monster) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SPAWN_MONSTER.getValue());
        p.writeInt(monster.getObjectId());
        p.writeByte(5);                 // controller 无，5
        p.writeInt(monster.getData().getMobId());
        p.skip(16);                     // 状态掩码占位（无状态）
        p.writeShort(monster.getX());
        p.writeShort(monster.getY());
        p.writeByte(0);                 // 姿态（MapleMonster 暂无 stance 字段，固定 0）
        p.writeShort(0);                // origin fh
        p.writeShort(0);                // fh
        p.writeByte(-2);                // newSpawn 淡入
        p.writeByte(0);                 // team
        p.writeInt(0);                  // itemEffect
        return p;
    }

    /**
     * 控制生成广播（0xEE）：只发给怪物的控制者，授予怪物移动控制权。
     *
     * <p>与 {@link #spawnMonster}（0xEC）同构 body，仅 opcode + 控制位不同：
     * 0xEC 的控制位现用 5（无控制者），0xEE 用 1（授予控制）。布局思路参考自
     * BeiDou-Server 的 PacketCreator.spawnMonsterControl（继承 OdinMS v83），
     * 实现自研；<b>control 位 1 vs 5 的确切客户端语义需真机 parity 确认</b>，
     * 上线前按 ARCHITECTURE M2 parity 录包回放核对。
     */
    public static OutPacket spawnMonsterControl(MapleMonster monster) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SPAWN_MONSTER_CONTROL.getValue());
        p.writeInt(monster.getObjectId());
        p.writeByte(1);                 // 授予控制权（对照 0xEC 无控制者 5）
        p.writeInt(monster.getData().getMobId());
        p.skip(16);                     // 状态掩码占位（无状态）
        p.writeShort(monster.getX());
        p.writeShort(monster.getY());
        p.writeByte(0);                 // 姿态
        p.writeShort(0);                // origin fh
        p.writeShort(0);                // fh
        p.writeByte(-2);                // newSpawn 淡入
        p.writeByte(0);                 // team
        p.writeInt(0);                  // itemEffect
        return p;
    }

    /**
     * 怪物移动广播（0xEF）：发给控制者以外的其他玩家，透传 MOVE_LIFE 移动片段。
     *
     * <p>布局：oid + movement 片段流（含各片段长度前缀）。思路参考自 BeiDou-Server
     * 的 PacketCreator.moveMonster，实现自研；待 parity 录包确认。
     */
    public static OutPacket moveMonster(int oid, byte[] movementBytes) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.MOVE_MONSTER.getValue());
        p.writeInt(oid);
        p.writeBytes(movementBytes);
        return p;
    }

    /**
     * 怪物移动响应（0xF0）：发给控制者，确认收到 MOVE_LIFE 并允许继续控制。
     *
     * <p>布局：oid + 移动序号（short 回显）+ 技能标志 + [skillId]。思路参考自
     * BeiDou-Server 的 PacketCreator.moveMonsterResponse，实现自研；待 parity 确认。
     */
    public static OutPacket moveMonsterResponse(int oid, short moveId, boolean skillMove, int skillId) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.MOVE_MONSTER_RESPONSE.getValue());
        p.writeInt(oid);
        p.writeShort(moveId);
        p.writeByte(skillMove ? 1 : 0);
        if (skillMove) {
            p.writeInt(skillId);
        }
        return p;
    }

    /** 怪物击杀广播（0xED）：oid + 2 + 2（动画淡出）。 */
    public static OutPacket killMonster(int oid) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.KILL_MONSTER.getValue());
        p.writeInt(oid);
        p.writeByte(2);
        p.writeByte(2);
        return p;
    }

    /** 怪物受击广播（0xF6）：oid + 0 + damage + 0 + 0（curhp/maxhp 传 0，普通广播）。 */
    public static OutPacket damageMonster(int oid, int damage) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.DAMAGE_MONSTER.getValue());
        p.writeInt(oid);
        p.writeByte(0);
        p.writeInt(damage);
        p.writeInt(0);
        p.writeInt(0);
        return p;
    }

    /** 攻击广播（0xBA/0xBB/0xBC）：cid + 计数 + 0x5B + skillLevel + [skill] + 姿态字节 + 0x0A + projectile + 每目标 oid+0+伤害行。 */
    public static OutPacket attack(long cid, SendOpcode opcode, int skillId, int skillLevel,
                                   byte numTargets, byte numDamage, int display, int direction, int stance, int speed,
                                   int[] targetOids, int[][] damages, int projectile) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(opcode.getValue());
        p.writeInt((int) cid);
        p.writeByte(((numTargets << 4) | numDamage));
        p.writeByte(0x5B);
        p.writeByte(skillLevel);
        if (skillLevel > 0) {
            p.writeInt(skillId);
        }
        p.writeByte(display);
        p.writeByte(direction);
        p.writeByte(stance);
        p.writeByte(speed);
        p.writeByte(0x0A);
        p.writeInt(projectile);
        for (int i = 0; i < targetOids.length; i++) {
            p.writeInt(targetOids[i]);
            p.writeByte(0);
            for (int d : damages[i]) {
                p.writeInt(d);
            }
        }
        return p;
    }
}
