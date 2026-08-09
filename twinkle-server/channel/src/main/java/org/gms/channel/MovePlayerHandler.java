package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.Character;
import org.gms.domain.game.map.MapleMap;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.replaceable.MovementSystem;

import java.io.ByteArrayOutputStream;

/**
 * 玩家移动处理（RecvOpcode.MOVE_PLAYER）。
 *
 * <p>v83 收包：opcode(2) + 9 字节头（跳过）→ 1 字节 numCommands → numCommands 个移动片段。
 * 片段 = 1 字节 command + 变长数据（绝对系 0/5/17 带 x/y，相对系 1/2/6/12/13/16/18/19/20/22
 * 带 deltaX/deltaY，瞬移 3/4/7/8/9 带 x/y，下跳 15 带 x/y，椅子 11 只改姿态）。
 * 最终落点 = 最后一个携带坐标的片段（常规行走最后必是 command 0 绝对移动）。
 * 布局思路参考自 BeiDou-Server 的 AbstractMovementPacketHandler，实现自研。
 *
 * <p>handler 只做"收包 → 解析落点 → 调 {@link MovementSystem} → 广播"，判定在 system
 * （版本门 + 落地），不写逻辑（红线 8/11/12）。广播透传原始移动段，回显排除自己。
 */
@Log4j2
public final class MovePlayerHandler implements PacketHandler {



    private final MovementSystem movementSystem;
    private final PlayerSessionRegistry sessions;

    public MovePlayerHandler(MovementSystem movementSystem, PlayerSessionRegistry sessions) {
        this.movementSystem = movementSystem;
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到移动包");
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close("未进图收到移动包");
            return;
        }
        MapleMap map = chr.getMapObject();
        if (map == null) {
            return;
        }

        byte[] movement = parseAndBroadcast(packet, chr, map);
        if (movement == null) {
            return;     // 空包/非法片段：丢弃不广播
        }
        sessions.broadcastToMap(map, GamePacketFactory.movePlayer(chr.getId(), movement), chr.getId());
    }

    /**
     * 解析移动片段并推进服务端落点；返回待透传的原始移动段（含 numCommands）。
     * <p>利用 {@link InPacket#getBytes()}（完整负载含 opcode）+ 已推进的 readerIndex 切段：
     * readerIndex 当前停在 9 字节头之后（NetworkSession 已消费 opcode 2 字节），
     * 从 raw 的 {@code readerIndex} 起读 numCommands 与片段，透传从 {@code readerIndex} 起。
     */
    private byte[] parseAndBroadcast(InPacket packet, Character chr, MapleMap map) {
        byte[] raw = packet.getBytes();
        int pos = packet.available() > 0 ? raw.length - packet.available() : raw.length;
        // 跳过 9 字节头
        if (raw.length - pos < 9 + 1) {
            return null;
        }
        pos += 9;
        int numCommands = raw[pos++] & 0xFF;
        if (numCommands < 1) {
            return null;    // 防空包/截断（思路参考 BeiDou 的 EmptyMovementException）
        }

        int start = pos;        // 透传起点（含 numCommands 之后的片段，不含 numCommands 本身）
        int x = chr.getX();
        int y = chr.getY();
        boolean hasPosition = false;
        for (int i = 0; i < numCommands && pos < raw.length; i++) {
            int command = raw[pos++] & 0xFF;
            switch (command) {
                case 0, 5, 17 -> {           // 绝对移动：x/y 覆盖
                    if (pos + 10 > raw.length) return null;
                    x = toShort(raw, pos);
                    y = toShort(raw, pos + 2);
                    hasPosition = true;
                    pos += 10;              // xwobble(2)+ywobble(2)+fh(2)+newstate(1)+duration(2)
                }
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> {   // 相对移动：delta 估算
                    if (pos + 7 > raw.length) return null;
                    x += toShort(raw, pos);
                    y += toShort(raw, pos + 2);
                    hasPosition = true;
                    pos += 7;               // newstate(1)+duration(2)
                }
                case 3, 4, 7, 8, 9, 11 -> { // 瞬移/突进/椅子：x/y 覆盖
                    if (pos + 9 > raw.length) return null;
                    x = toShort(raw, pos);
                    y = toShort(raw, pos + 2);
                    hasPosition = true;
                    pos += 9;               // xwobble(2)+ywobble(2)+newstate(1)
                }
                case 15 -> {                 // 下跳：x/y 覆盖
                    if (pos + 15 > raw.length) return null;
                    x = toShort(raw, pos);
                    y = toShort(raw, pos + 2);
                    hasPosition = true;
                    pos += 15;              // xwobble/ywobble/fh/ofh/newstate/duration
                }
                case 10 -> pos += 1;         // 换装，忽略
                case 14 -> pos += 9;         // 下跳未定，跳过
                case 21 -> pos += 3;         // Aran 特殊，跳过
                default -> {
                    log.warn("未知移动片段 command={}，丢弃移动包", command);
                    return null;
                }
            }
        }
        if (pos > raw.length) {
            return null;    // 截断包
        }

        if (hasPosition) {
            // 版本门 + 落地判定在 system
            movementSystem.move(chr, map, x, y);
        }

        // 透传：numCommands(1) + 片段（不含 9 字节头）
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        bout.write(numCommands);
        bout.write(raw, start, raw.length - start);
        return bout.toByteArray();
    }

    private static int toShort(byte[] raw, int pos) {
        return (raw[pos] & 0xFF) | ((raw[pos + 1] & 0xFF) << 8);
    }
}
