package org.gms.channel.admin;

import org.gms.channel.PlayerSessionRegistry;
import org.gms.net.packet.PacketSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-1 第②路集成测试：管理侧经 {@link AdminService} 踢下线（架构 M3-1 数据三路第②路）。
 *
 * <p>验证 ChannelAdminService.kick 经会话注册表关闭在线会话；不在线角色返回 false（不报错）。
 * 用假会话验证关闭调用触发（真实 NetworkSession 的 close 由 Netty 通道关闭，E2E 覆盖）。
 */
class ChannelAdminServiceTest {

    @Test
    void kickClosesOnlineSession() {
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        ChannelAdminService admin = new ChannelAdminService(
                new org.gms.channel.PlayerStorage(), sessions, 1);

        AtomicBoolean closed = new AtomicBoolean(false);
        PacketSession fakeSession = new PacketSession() {
            @Override
            public void send(org.gms.net.packet.OutPacket packet) {
            }

            @Override
            public void close(String reason) {
                closed.set(true);
            }

            @Override
            public org.gms.net.packet.SessionStage stage() {
                return org.gms.net.packet.SessionStage.IN_GAME;
            }

            @Override
            public void transition(org.gms.net.packet.SessionStage stage) {
            }

            @Override
            public <T> T getAttr(String key) {
                return null;
            }

            @Override
            public void setAttr(String key, Object value) {
            }
        };
        sessions.register(42L, fakeSession);

        assertThat(admin.kick(42L)).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    void kickOfflineCharacterReturnsFalse() {
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        ChannelAdminService admin = new ChannelAdminService(
                new org.gms.channel.PlayerStorage(), sessions, 1);

        assertThat(admin.kick(999L)).isFalse();
    }
}
