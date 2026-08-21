package org.gms.httpapi.api.v1.mapper;

import org.gms.httpapi.api.v1.dto.response.AccountResponse;
import org.gms.httpapi.api.v1.dto.response.ApiKeySummaryResponse;
import org.gms.httpapi.api.v1.dto.response.CharacterResponse;
import org.gms.httpapi.api.v1.dto.response.IdentityResponse;
import org.gms.httpapi.api.v1.dto.response.IssuedApiKeyResponse;
import org.gms.httpapi.api.v1.dto.response.OnlinePlayerResponse;
import org.gms.httpapi.api.v1.dto.response.OnlineResponse;
import org.gms.httpapi.application.admin.AdminApiService;
import org.gms.httpapi.auth.ApiKeyService;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.service.admin.AdminService;

import java.time.Instant;
import java.util.List;

/** 将版本无关的应用结果映射为冻结的 v1 DTO。 */
public final class PublicApiV1Mapper {

    public static OnlineResponse online(List<AdminService.OnlinePlayer> players) {
        List<OnlinePlayerResponse> responsePlayers = players.stream()
                .map(player -> new OnlinePlayerResponse(player.characterId(), player.name(),
                        player.mapId(), player.level(), player.job()))
                .toList();
        return new OnlineResponse(responsePlayers.size(), responsePlayers);
    }

    public static AccountResponse account(AdminApiService.AccountView account) {
        return new AccountResponse(account.id(), account.name(), account.banned(), account.gender(),
                account.characterSlots());
    }

    public static CharacterResponse character(AdminApiService.CharacterView character) {
        return new CharacterResponse(character.id(), character.name(), character.level(),
                character.job(), character.map(), character.meso());
    }

    public static IdentityResponse identity(String contractVersion, ApiPrincipal principal,
                                            ServerIdentity serverIdentity) {
        return new IdentityResponse(
                contractVersion,
                new IdentityResponse.Subject(principal.subjectId(), principal.subjectDisplayName()),
                new IdentityResponse.Credential(principal.credentialId(), "api_key",
                        principal.expiresAt()),
                new IdentityResponse.Server(serverIdentity.serverId(), serverIdentity.displayName(),
                        serverIdentity.environment(), serverIdentity.version()),
                principal.scopes(),
                List.of(new IdentityResponse.ResourceSelector(
                        "server", "ids", List.of(principal.serverId()))),
                principal.permissionVersion(),
                Instant.now().toString());
    }

    public static IssuedApiKeyResponse issuedKey(ApiKeyService.IssuedKey key) {
        return new IssuedApiKeyResponse(key.id(), key.credentialId(), key.keyPrefix(), key.token(),
                key.displayName(), key.subjectId(), key.ownerAccountId(), key.scopes(),
                key.serverId(), key.createdAt(), key.expiresAt(), key.rotatedFromPrefix());
    }

    public static ApiKeySummaryResponse keySummary(ApiKeyService.KeySummary key) {
        return new ApiKeySummaryResponse(key.id(), key.credentialId(), key.keyPrefix(),
                key.displayName(), key.subjectId(), key.ownerAccountId(), key.scopes(),
                key.serverId(), key.createdAt(), key.expiresAt(), key.disabledAt(), key.revokedAt(),
                key.rotatedFromPrefix(), key.lastUsedAt());
    }

    private PublicApiV1Mapper() {
    }
}
