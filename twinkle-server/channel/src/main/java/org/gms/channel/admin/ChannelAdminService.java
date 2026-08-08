package org.gms.channel.admin;

import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.domain.game.Character;
import org.gms.net.packet.PacketSession;
import org.gms.service.admin.AdminService;

/**
 * 频道侧 {@link AdminService} 实现（架构 M3-1 第②路：管理侧经 service 接口访问频道）。
 *
 * <p>只读快照经 DTO 拷贝返回（{@link Character} 是内存态权威对象，绝不出进程/出模块边界，
 * 防 http-api 直踩游戏内存）。踢下线走会话注册表关闭连接。
 *
 * <p>装配由 bootstrap 接线（本类不加 @Singleton，避免与 @Bean 双份）。
 */
public final class ChannelAdminService implements AdminService {

    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final long channelId;

    public ChannelAdminService(PlayerStorage players, PlayerSessionRegistry sessions, long channelId) {
        this.players = players;
        this.sessions = sessions;
        this.channelId = channelId;
    }

    @Override
    public ChannelSummary onlineSummary() {
        java.util.List<OnlinePlayer> snapshot = players.all().stream()
                .map(this::toDto)
                .sorted((a, b) -> Long.compare(a.characterId(), b.characterId()))
                .toList();
        return new ChannelSummary(snapshot.size(), channelId, snapshot);
    }

    @Override
    public boolean kick(long characterId) {
        PacketSession session = sessions.get(characterId);
        if (session == null) {
            return false;
        }
        session.close("管理侧踢下线: characterId=" + characterId);
        // 断链注销由 DisconnectListener 完成（ChannelServer 装配），此处只触发关闭。
        return true;
    }

    private OnlinePlayer toDto(Character chr) {
        return new OnlinePlayer(chr.getId(), chr.getName(), chr.getMap(), chr.getLevel(), chr.getJob());
    }
}
