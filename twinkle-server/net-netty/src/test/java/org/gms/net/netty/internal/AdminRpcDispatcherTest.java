package org.gms.net.netty.internal;

import org.gms.diagnostics.PacketTrace;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** AdminService 在线背包 DTO 的 split RPC 分发测试。 */
public final class AdminRpcDispatcherTest {

    @Test
    public void dispatchesInventorySnapshotAsStableDto() {
        AdminRpcDispatcher dispatcher = new AdminRpcDispatcher(admin());

        InternalProtocol.RpcResponse response = dispatcher.dispatch(
                "inventorySnapshot", new String[]{JsonCodec.encode(42L)});

        assertThat(response.ok()).isTrue();
        AdminService.PlayerInventory decoded = JsonCodec.decode(
                response.value(), AdminService.PlayerInventory.class.getName());
        assertThat(decoded.characterId()).isEqualTo(42L);
        assertThat(decoded.items()).singleElement().satisfies(item -> {
            assertThat(item.itemType()).isEqualTo("pet");
            assertThat(item.petId()).isEqualTo(9001);
            assertThat(item.pet().name()).isEqualTo("小黑");
        });
    }

    @Test
    public void dispatchesWzReloadResult() {
        InternalProtocol.RpcResponse response = new AdminRpcDispatcher(admin())
                .dispatch("reloadWz", new String[0]);

        assertThat(response.ok()).isTrue();
        AdminService.WzReloadResult decoded = JsonCodec.decode(
                response.value(), AdminService.WzReloadResult.class.getName());
        assertThat(decoded.version()).isEqualTo(2);
    }

    @Test
    public void dispatchesPacketTraceConfigurationAsStableDto() {
        PacketTrace.Config config = new PacketTrace.Config(PacketTrace.FilterMode.EXCLUDE,
                Set.of(PacketTrace.Direction.INBOUND), Set.of("MOVE_LIFE"), 4096);

        InternalProtocol.RpcResponse response = new AdminRpcDispatcher(admin()).dispatch(
                "startPacketTrace", new String[]{JsonCodec.encode(42L), JsonCodec.encode(config)});

        assertThat(response.ok()).isTrue();
        PacketTrace.Snapshot decoded = JsonCodec.decode(
                response.value(), PacketTrace.Snapshot.class.getName());
        assertThat(decoded.enabled()).isTrue();
        assertThat(decoded.config().opcodeNames()).containsExactly("MOVE_LIFE");
    }

    private static AdminService admin() {
        return new AdminService() {
            @Override
            public PlayerInventory inventorySnapshot(long characterId) {
                PetView pet = new PetView("小黑", 12, 3456, 87, 3, 4, 17_500, 5);
                InventoryItemView item = new InventoryItemView(
                        5, 2, "pet", 5_000_000, 1, 0, 9001, "", 0, 0,
                        null, pet);
                return new PlayerInventory(characterId, "Hero", 7L, List.of(item));
            }

            @Override
            public ChannelSummary onlineSummary() {
                return new ChannelSummary(0, 1, List.of());
            }

            @Override
            public boolean kick(long characterId) {
                return false;
            }

            @Override
            public PacketTrace.Snapshot startPacketTrace(long characterId, PacketTrace.Config config) {
                return new PacketTrace.Snapshot(true, true, config, 0, 0, List.of());
            }

            @Override
            public int reloadScripts() {
                return 0;
            }

            @Override
            public WzReloadResult reloadWz() {
                return new WzReloadResult(2, java.util.Map.of(), java.util.Map.of());
            }

            @Override
            public void requestRestart() {
            }

            @Override
            public RestartCoordinator.Phase restartPhase() {
                return RestartCoordinator.Phase.RUNNING;
            }
        };
    }
}
