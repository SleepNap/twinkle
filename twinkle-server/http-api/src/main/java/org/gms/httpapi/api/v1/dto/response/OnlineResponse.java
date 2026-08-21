package org.gms.httpapi.api.v1.dto.response;

import java.util.List;

/** v1 在线概览。 */
public record OnlineResponse(int onlineCount, List<OnlinePlayerResponse> players) {
}
