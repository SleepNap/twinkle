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

        Map<String, Object> catalog = service.catalog(healthOnly, "read-only", "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) catalog.get("tools");
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

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>)
                service.catalog(otherServer, "", "").get("tools");
        assertThat(tools).isEmpty();
    }

    private static ServerIdentity identity() {
        return new ServerIdentity("server-1", "一服", "test", "1.0.0");
    }

    private static ApiPrincipal principal(Set<String> scopes, String serverId) {
        return new ApiPrincipal(1L, "cred_1", "prefix", "subject_1", "开发者",
                "test key", scopes, serverId, null, "perm_1");
    }
}
