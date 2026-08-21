package org.gms.httpapi.execution;

import org.gms.httpapi.identity.ServerIdentity;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在线背包 Tool 输入边界与稳定输出测试。 */
public final class PlayerInventoryToolTest {

    @Test
    public void returnsOnlineMemorySnapshot() {
        PlayerInventoryTool tool = new PlayerInventoryTool(admin(), identity());

        Map<String, Object> output = tool.read(Map.of("characterId", "42"), "req_1", "exec_1");

        assertThat(output.get("serverId")).isEqualTo("server-1");
        assertThat(output.get("characterId")).isEqualTo("42");
        assertThat(output.get("name")).isEqualTo("Hero");
        assertThat(output.get("itemCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) output.get("items");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.get("itemType")).isEqualTo("pet");
            assertThat(item.get("petId")).isEqualTo("9001");
            assertThat(item.get("pet")).isInstanceOf(Map.class);
        });
    }

    @Test
    public void rejectsInvalidOrOfflineCharacter() {
        PlayerInventoryTool tool = new PlayerInventoryTool(admin(), identity());

        assertThatThrownBy(() -> tool.read(Map.of("characterId", 42), "req_1", "exec_1"))
                .isInstanceOf(ToolProtocolException.class)
                .extracting("code").isEqualTo("invalid_input");
        assertThatThrownBy(() -> tool.read(Map.of("characterId", "99"), "req_2", "exec_2"))
                .isInstanceOf(ToolProtocolException.class)
                .extracting("code").isEqualTo("resource_not_found");
    }

    private static AdminService admin() {
        return new AdminService() {
            @Override
            public PlayerInventory inventorySnapshot(long characterId) {
                if (characterId != 42L) {
                    return null;
                }
                PetView pet = new PetView("小黑", 12, 3456, 87, 3, 4, 17_500, 5);
                InventoryItemView item = new InventoryItemView(
                        5, 2, "pet", 5_000_000, 1, 0, 9001, "", 0, 0,
                        null, pet);
                return new PlayerInventory(42L, "Hero", 7L, List.of(item));
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
            public WzReloadResult reloadWz() {
                return new WzReloadResult(2, Map.of(), Map.of());
            }

            @Override
            public void requestRestart() {
            }

            @Override
            public org.gms.hotreload.RestartCoordinator.Phase restartPhase() {
                return org.gms.hotreload.RestartCoordinator.Phase.RUNNING;
            }
        };
    }

    private static ServerIdentity identity() {
        return new ServerIdentity("server-1", "一服", "test", "1.0.0");
    }
}
