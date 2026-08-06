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
