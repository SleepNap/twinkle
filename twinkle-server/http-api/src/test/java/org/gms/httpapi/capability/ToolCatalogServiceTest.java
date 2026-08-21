package org.gms.httpapi.capability;

import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.auth.ApiScopes;
import org.gms.httpapi.identity.ServerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Capability 可见性由服务端 scope 与 server resource 共同裁剪。 */
public final class ToolCatalogServiceTest {

    @Test
    public void catalogOnlyContainsVisibleToolsAndSummaryOmitsSchemas() {
        ToolCatalogService service = new ToolCatalogService(identity());
        ApiPrincipal healthOnly = principal(Set.of(ApiScopes.SERVER_HEALTH_READ), "server-1");

        ToolCatalogService.Catalog catalog = service.catalog(healthOnly, "read-only", "");
        List<Map<String, Object>> tools = catalog.tools();
        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.get("toolId")).isEqualTo(ToolCatalogService.HEALTH_TOOL);
            assertThat(tool).doesNotContainKeys("inputSchema", "outputSchema");
        });
        assertThat(service.detail(healthOnly, ToolCatalogService.ONLINE_TOOL)).isEmpty();
        assertThat(service.detail(healthOnly, ToolCatalogService.HEALTH_TOOL)).isPresent();
    }

    @Test
    public void serverScopeMismatchHidesAllTools() {
        ToolCatalogService service = new ToolCatalogService(identity());
        ApiPrincipal otherServer = principal(Set.of(ApiScopes.SERVER_HEALTH_READ), "server-2");

        List<Map<String, Object>> tools = service.catalog(otherServer, "", "").tools();
        assertThat(tools).isEmpty();
    }

    @Test
    public void inventoryToolRequiresDedicatedScopeAndDeclaresCharacterInput() {
        ToolCatalogService service = new ToolCatalogService(identity());
        ApiPrincipal principal = principal(Set.of(ApiScopes.PLAYER_INVENTORY_READ), "server-1");

        assertThat(service.detail(principal, ToolCatalogService.INVENTORY_TOOL)).isPresent()
                .get().satisfies(detail -> {
                    assertThat(detail.detail().get("toolId"))
                            .isEqualTo(ToolCatalogService.INVENTORY_TOOL);
                    assertThat(detail.detail().toString())
                            .contains("characterId").contains("player.inventory:read");
                });
        assertThat(service.detail(principal, ToolCatalogService.ONLINE_TOOL)).isEmpty();
    }

    private static ServerIdentity identity() {
        return new ServerIdentity("server-1", "一服", "test", "1.0.0");
    }

    private static ApiPrincipal principal(Set<String> scopes, String serverId) {
        return new ApiPrincipal(1L, "cred_1", "prefix", "subject_1", "开发者",
                "test key", scopes, serverId, null, "perm_1");
    }
}
