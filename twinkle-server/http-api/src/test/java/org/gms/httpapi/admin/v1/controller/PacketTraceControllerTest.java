package org.gms.httpapi.admin.v1.controller;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import org.gms.diagnostics.PacketTrace;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 封包监听管理 API 的校验、审计摘要与无缓存响应测试。 */
public final class PacketTraceControllerTest {
    @Test
    public void startsTraceWithNormalizedFilterAndSafeAuditSummary() {
        FakeAdmin admin = new FakeAdmin();
        PacketTraceController controller = new PacketTraceController(admin);
        HttpRequest<?> request = HttpRequest.PUT("/", "");

        var response = controller.start(request, 42L, new PacketTraceController.StartRequest(
                "exclude", List.of("inbound"), List.of("move_life", "0x1234"), 2048));

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.getHeaders().get(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(admin.config.mode()).isEqualTo(PacketTrace.FilterMode.EXCLUDE);
        assertThat(admin.config.directions()).containsExactly(PacketTrace.Direction.INBOUND);
        assertThat(admin.config.opcodeNames()).containsExactlyInAnyOrder("MOVE_LIFE", "0X1234");
        assertThat(request.getAttribute("twinkle.admin.after-summary", String.class).orElseThrow())
                .contains("characterId=42", "packetTraceEnabled=true", "opcodeCount=2")
                .doesNotContain("payload");
    }

    @Test
    public void rejectsEmptyIncludeFilter() {
        PacketTraceController controller = new PacketTraceController(new FakeAdmin());

        var response = controller.start(HttpRequest.PUT("/", ""), 42L,
                new PacketTraceController.StartRequest(
                        "INCLUDE", List.of("INBOUND"), List.of(), 4096));

        assertThat(response.code()).isEqualTo(400);
    }

    @Test
    public void returnsNotFoundWhenCharacterIsOffline() {
        FakeAdmin admin = new FakeAdmin();
        admin.online = false;
        PacketTraceController controller = new PacketTraceController(admin);

        assertThat(controller.snapshot(42L, 0, 200).code()).isEqualTo(404);
    }

    private static final class FakeAdmin implements AdminService {
        private boolean online = true;
        private PacketTrace.Config config;

        @Override
        public ChannelSummary onlineSummary() {
            return new ChannelSummary(online ? 1 : 0, 1, List.of());
        }

        @Override
        public boolean kick(long characterId) {
            return false;
        }

        @Override
        public PacketTrace.Snapshot startPacketTrace(long characterId, PacketTrace.Config nextConfig) {
            config = nextConfig;
            return online ? snapshot(true, nextConfig) : null;
        }

        @Override
        public PacketTrace.Snapshot packetTraceSnapshot(long characterId, long afterSequence, int limit) {
            return online ? (config == null ? PacketTrace.Snapshot.notConfigured() : snapshot(true, config)) : null;
        }

        @Override
        public PacketTrace.Snapshot stopPacketTrace(long characterId) {
            return online ? snapshot(false, config) : null;
        }

        @Override
        public int reloadScripts() {
            return 0;
        }

        @Override
        public WzReloadResult reloadWz() {
            return new WzReloadResult(0, java.util.Map.of(), java.util.Map.of());
        }

        @Override
        public void requestRestart() {
        }

        @Override
        public RestartCoordinator.Phase restartPhase() {
            return RestartCoordinator.Phase.RUNNING;
        }

        private static PacketTrace.Snapshot snapshot(boolean enabled, PacketTrace.Config config) {
            return new PacketTrace.Snapshot(true, enabled, config, 0, 0, List.of());
        }
    }
}
