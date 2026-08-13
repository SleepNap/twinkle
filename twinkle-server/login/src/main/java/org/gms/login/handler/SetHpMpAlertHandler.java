package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

/**
 * 设置 HP/MP 警报阈值（RecvOpcode.SET_HPMPALERT 0x1000）。
 *
 * <p>部分 v83 客户端在连入登录服早期即发本包（自动吃药阈值挡位，参考项目仅在
 * 频道侧注册为游戏内功能）。登录阶段无角色实体，阈值无处落地，这里仅按协议
 * 读掉两个阈值字节（hp/mp 各一，0~19 挡），不产生任何副作用，避免登录日志
 * 出现"未注册的收包 opcode: 0x1000"噪音。
 *
 * <p>真正的按角色自动吃药存储属于频道侧游戏功能（M2 核心机制生命恢复系），
 * 本 handler 只覆盖登录连接出现的场景。包格式思路参考自 BeiDou-Server
 * SetHpMpAlertHandler，实现自研。
 */
@Log4j2
public final class SetHpMpAlertHandler implements PacketHandler {


    @Override
    public void handle(PacketSession session, InPacket packet) {
        // 登录阶段无角色，读掉阈值字节即可；缺字节的畸形包直接忽略（不中断连接）
        if (packet.available() >= 2) {
            packet.readByte(); // hp 阈值挡位（0~19，登录阶段忽略）
            packet.readByte(); // mp 阈值挡位（0~19，登录阶段忽略）
            log.debug(I18n.message("log.hpmp_alert.ignored_no_character"));
        } else {
            log.debug(I18n.message("log.hpmp_alert.ignored_short_packet"));
        }
    }
}
