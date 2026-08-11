package org.gms.net.netty.internal;

import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            public int reloadScripts() {
                return 0;
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
