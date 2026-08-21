package org.gms.httpapi.auth;

import java.util.Set;

/** 公共 API 的稳定 scope 名称。 */
public final class ApiScopes {

    public static final String GAME_READ = "game:read";
    public static final String GAME_WRITE = "game:write";
    public static final String SERVER_HEALTH_READ = "server.health:read";
    public static final String PLAYER_ONLINE_READ = "player.online:read";
    public static final String PLAYER_INVENTORY_READ = "player.inventory:read";
    public static final String SERVER_RELOAD_READ = "server.reload:read";
    public static final String SERVER_RELOAD_WRITE = "server.reload:write";
    public static final String SERVER_CONFIG_WRITE = "server.config:write";
    public static final String KEYS_MANAGE = "keys:manage";
    public static final String EVENTS_READ = "events:read";
    public static final String EVENTS_WRITE = "events:write";

    public static final Set<String> SUPPORTED = Set.of(
            SERVER_HEALTH_READ, PLAYER_ONLINE_READ, PLAYER_INVENTORY_READ,
            SERVER_RELOAD_READ, SERVER_RELOAD_WRITE, SERVER_CONFIG_WRITE,
            GAME_READ, GAME_WRITE, KEYS_MANAGE, EVENTS_READ, EVENTS_WRITE);

    private ApiScopes() {
    }
}
