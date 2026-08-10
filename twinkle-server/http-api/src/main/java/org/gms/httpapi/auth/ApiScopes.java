package org.gms.httpapi.auth;

import java.util.Set;

/** twish 能力面的稳定 scope 名称。 */
public final class ApiScopes {

    public static final String GAME_READ = "game:read";
    public static final String GAME_WRITE = "game:write";
    public static final String SERVER_HEALTH_READ = "server.health:read";
    public static final String PLAYER_ONLINE_READ = "player.online:read";
    public static final String AI_USE = "ai:use";
    public static final String KEYS_MANAGE = "keys:manage";
    public static final String EVENTS_READ = "events:read";
    public static final String EVENTS_WRITE = "events:write";

    public static final Set<String> SUPPORTED = Set.of(
            SERVER_HEALTH_READ, PLAYER_ONLINE_READ,
            GAME_READ, GAME_WRITE, AI_USE, KEYS_MANAGE, EVENTS_READ, EVENTS_WRITE);

    private ApiScopes() {
    }
}
