package org.gms.httpapi.execution;

import org.gms.event.InProcessEventBus;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.auth.ApiScopes;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.httpapi.mirror.OnlinePlayerMirror;
import org.gms.service.admin.OnlinePlayerEvents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在线分页的稳定排序、ID 字符串化、Cursor 防篡改与快照冲突。 */
public final class OnlinePlayerPageServiceTest {

    @Test
    public void cursorIsBoundSignedAndSnapshotAware() {
        InProcessEventBus bus = new InProcessEventBus();
        try (OnlinePlayerMirror mirror = new OnlinePlayerMirror(bus)) {
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(3L, "C", 3, 30, 300));
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(1L, "A", 1, 10, 100));
            OnlinePlayerPageService service = new OnlinePlayerPageService(mirror, identity(),
                    "online-player-test-cursor-signing-key");

            Map<String, Object> first = service.page(principal(),
                    Map.of("pageSize", 1), "req_1", "exec_1");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> players = (List<Map<String, Object>>) first.get("players");
            assertThat(players).singleElement().satisfies(player ->
                    assertThat(player.get("characterId")).isEqualTo("1"));
            String cursor = (String) first.get("nextCursor");

            assertThatThrownBy(() -> service.page(principal(),
                    Map.of("pageSize", 1, "cursor", cursor + "x"), "req_2", "exec_2"))
                    .isInstanceOf(ToolProtocolException.class)
                    .extracting(error -> ((ToolProtocolException) error).code())
                    .isEqualTo("invalid_input");

            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(2L, "B", 2, 20, 200));
            assertThatThrownBy(() -> service.page(principal(),
                    Map.of("pageSize", 1, "cursor", cursor), "req_3", "exec_3"))
                    .isInstanceOf(ToolProtocolException.class)
                    .extracting(error -> ((ToolProtocolException) error).code())
                    .isEqualTo("snapshot_changed");
        }
    }

    private static ServerIdentity identity() {
        return new ServerIdentity("server-1", "一服", "test", null);
    }

    private static ApiPrincipal principal() {
        return new ApiPrincipal(1L, "cred_1", "prefix", "subject_1", "开发者",
                "test key", Set.of(ApiScopes.PLAYER_ONLINE_READ), "server-1", null, "perm_1");
    }
}
