package org.gms.net.packet;

import org.gms.net.opcodes.RecvOpcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HandlerRegistry 版本化验证（红线 13：贡献点从第一天版本化）。
 */
class HandlerRegistryTest {

    private HandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new HandlerRegistry();
    }

    @Test
    void registerThenFind() {
        PacketHandler handler = (session, packet) -> {
        };
        registry.register(RecvOpcode.LOGIN_PASSWORD, handler);

        assertThat(registry.find(RecvOpcode.LOGIN_PASSWORD.getValue())).contains(handler);
        assertThat(registry.find(RecvOpcode.SERVERLIST_REQUEST.getValue())).isEmpty();
        assertThat(registry.registeredCount()).isEqualTo(1);
    }

    @Test
    void duplicateRegisterRejected() {
        PacketHandler handler = (session, packet) -> {
        };
        registry.register(RecvOpcode.CHAR_SELECT, handler);

        assertThatThrownBy(() -> registry.register(RecvOpcode.CHAR_SELECT, handler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已注册");
    }

    @Test
    void replaceWithHigherVersionWins() {
        PacketHandler v1 = (session, packet) -> {
        };
        PacketHandler v2 = (session, packet) -> {
        };
        registry.register(RecvOpcode.PONG, v1, 1);
        registry.replace(RecvOpcode.PONG, v2, 2);

        assertThat(registry.find(RecvOpcode.PONG.getValue())).contains(v2);
    }

    @Test
    void replaceWithLowerVersionRejected() {
        PacketHandler v2 = (session, packet) -> {
        };
        PacketHandler v1 = (session, packet) -> {
        };
        registry.register(RecvOpcode.PONG, v2, 2);

        assertThatThrownBy(() -> registry.replace(RecvOpcode.PONG, v1, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("须高于");
    }
}
