package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.PlayerCharacter;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 公会收包（{@code RecvOpcode.GUILD_OPERATION 0x7E} 与 {@code DENY_GUILD_REQUEST 0x7F}）。
 *
 * <p>包体首字节即 mode，没有子操作前缀。本期只实现最小闭环的 5 个 mode：0x02 创建、
 * 0x05 邀请、0x06 接受、0x07 退出、0x10 公告；开除（0x08）、职级称号（0x0D）、改职级（0x0E）、
 * 徽章（0x0F）、多人共建确认（0x1E）一律静默丢弃，客户端不会收到回执。
 *
 * <p>这里只做长度校验与 stage 校验，业务规则全部在 {@link GuildService}；长度不足直接丢弃，
 * 不让 InPacket 抛越界异常。
 */
@Log4j2
public final class GuildHandler implements PacketHandler {

    private final GuildService guild;

    public GuildHandler(GuildService guild) {
        this.guild = guild;
    }

    /**
     * 收包入口：任何畸形长度字段都只丢弃本包，绝不外抛。
     *
     * <p>{@code InPacket.readString()} 遇到伪造的长度前缀（例如 0xFFFF）会抛越界异常，而
     * {@code HandlerRegistry} 对未捕获异常的处理是记错误日志并直接关闭会话——也就是说一个
     * 几字节的伪造包就能把在线玩家踢下线。因此这里必须把异常收敛在本 handler 内。
     */
    @Override
    public void handle(PacketSession session, InPacket packet) {
        try {
            dispatch(session, packet);
        } catch (RuntimeException error) {
            log.debug(I18n.message("log.guild.malformed_packet"), error);
        }
    }

    private void dispatch(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) return;
        PlayerCharacter character = session.getAttr("character");
        if (character == null) return;
        if (packet.available() < 1) return;
        int mode = packet.readByte() & 0xFF;
        switch (mode) {
            case 0x02 -> {
                if (packet.available() < 2) return;
                guild.create(session, character, packet.readString());
            }
            case 0x05 -> {
                if (packet.available() < 2) return;
                guild.invite(session, character, packet.readString());
            }
            case 0x06 -> {
                if (packet.available() < 8) return;
                int guildId = packet.readInt();
                int characterId = packet.readInt();
                guild.accept(session, character, guildId, characterId);
            }
            case 0x07 -> {
                if (packet.available() < 5) return;
                int characterId = packet.readInt();
                String name = packet.readString();
                guild.leave(session, character, characterId, name);
            }
            case 0x10 -> {
                if (packet.available() < 2) return;
                guild.notice(session, character, packet.readString());
            }
            default -> {
                // 本期未实现的 mode 静默丢弃：客户端不会看到"操作成功"，也不会卡在中间态。
            }
        }
    }

    /** 拒绝邀请（{@code DENY_GUILD_REQUEST 0x7F}）：包体是 byte + Maple 字符串，byte 忽略。 */
    public void deny(PacketSession session, InPacket packet) {
        try {
            if (session.stage() != SessionStage.IN_GAME) return;
            PlayerCharacter character = session.getAttr("character");
            if (character == null) return;
            if (packet.available() < 1) return;
            packet.readByte();
            if (packet.available() < 2) return;
            packet.readString();
            guild.denyInvitation(session, character);
        } catch (RuntimeException error) {
            log.debug(I18n.message("log.guild.malformed_packet"), error);
        }
    }
}
