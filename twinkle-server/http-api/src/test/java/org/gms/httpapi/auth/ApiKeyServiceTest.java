package org.gms.httpapi.auth;

import org.gms.data.entity.ApiKeyRecord;
import org.gms.data.repo.ApiKeyRepository;
import org.gms.httpapi.identity.ServerIdentity;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API-key 的明文隔离、scope 和吊销行为。 */
public final class ApiKeyServiceTest {

    @Test
    public void issuedTokenAuthenticatesButPlaintextIsNeverStored() {
        InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
        ApiKeyService service = service(repository);

        ApiKeyService.IssuedKey issued = service.issue(service.bootstrapPrincipal(), "twish-test", 7L,
                Set.of(ApiScopes.GAME_READ), null);

        assertThat(issued.token()).startsWith("twk_");
        assertThat(repository.records).hasSize(1);
        assertThat(repository.records.getFirst().getSecretHash())
                .hasSize(64)
                .isNotEqualTo(issued.token());
        assertThat(service.authenticate(issued.token()))
                .get()
                .satisfies(principal -> {
                    assertThat(principal.keyPrefix()).isEqualTo(issued.keyPrefix());
                    assertThat(principal.permits(ApiScopes.GAME_READ)).isTrue();
                    assertThat(principal.permits(ApiScopes.GAME_WRITE)).isFalse();
                });
        assertThat(service.authenticate(issued.token() + "tampered")).isEmpty();
    }

    @Test
    public void revokedKeyIsRejectedAndUnknownScopesCannotBeIssued() {
        InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
        ApiKeyService service = service(repository);
        ApiKeyService.IssuedKey issued = service.issue(service.bootstrapPrincipal(), "twish-test", null,
                Set.of(ApiScopes.GAME_READ), null);

        assertThat(service.revoke(service.bootstrapPrincipal(), issued.keyPrefix())).isTrue();
        assertThat(service.authenticate(issued.token())).isEmpty();
        assertThatThrownBy(() -> service.issue(service.bootstrapPrincipal(), "bad", null,
                Set.of("database:root"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 scope");
    }

    @Test
    public void nonBootstrapIssuerCannotExpandScopesAndRotationRevokesOldSecret() {
        InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
        ApiKeyService service = service(repository);
        ApiKeyService.IssuedKey manager = service.issue(service.bootstrapPrincipal(), "manager", null,
                Set.of(ApiScopes.KEYS_MANAGE, ApiScopes.SERVER_HEALTH_READ), null);
        ApiPrincipal managerPrincipal = service.authenticate(manager.token()).orElseThrow();

        assertThatThrownBy(() -> service.issue(managerPrincipal, "expanded", null,
                Set.of(ApiScopes.PLAYER_ONLINE_READ), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过签发者");

        ApiKeyService.IssuedKey rotated = service.rotate(service.bootstrapPrincipal(), manager.keyPrefix())
                .orElseThrow();
        assertThat(service.authenticate(manager.token())).isEmpty();
        assertThat(service.authenticate(rotated.token())).isPresent();
        assertThat(rotated.rotatedFromPrefix()).isEqualTo(manager.keyPrefix());
    }

    @Test
    public void disabledCredentialFailsImmediatelyAndCanBeReenabled() {
        InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
        ApiKeyService service = service(repository);
        ApiKeyService.IssuedKey issued = service.issue(service.bootstrapPrincipal(), "desktop", null,
                Set.of(ApiScopes.SERVER_HEALTH_READ), null);

        assertThat(service.setDisabled(service.bootstrapPrincipal(), issued.keyPrefix(), true)).isTrue();
        assertThat(service.authenticate(issued.token())).isEmpty();
        assertThat(service.setDisabled(service.bootstrapPrincipal(), issued.keyPrefix(), false)).isTrue();
        assertThat(service.authenticate(issued.token())).isPresent();
    }

    @Test
    public void scopeUpdateTakesEffectWithoutReplacingTheSecret() {
        InMemoryApiKeyRepository repository = new InMemoryApiKeyRepository();
        ApiKeyService service = service(repository);
        ApiKeyService.IssuedKey issued = service.issue(service.bootstrapPrincipal(), "ai-operator", null,
                Set.of(ApiScopes.SERVER_HEALTH_READ), null);

        ApiKeyService.KeySummary updated = service.updateScopes(service.bootstrapPrincipal(),
                issued.keyPrefix(), Set.of(ApiScopes.SERVER_HEALTH_READ, ApiScopes.AI_USE)).orElseThrow();

        assertThat(updated.scopes()).containsExactlyInAnyOrder(ApiScopes.SERVER_HEALTH_READ, ApiScopes.AI_USE);
        assertThat(service.authenticate(issued.token())).get()
                .satisfies(principal -> assertThat(principal.permits(ApiScopes.AI_USE)).isTrue());
    }

    private static ApiKeyService service(InMemoryApiKeyRepository repository) {
        return new ApiKeyService(repository, "",
                new ServerIdentity("server-test", "测试服", "test", "1.0.0"),
                new SecureRandom());
    }

    private static final class InMemoryApiKeyRepository implements ApiKeyRepository {

        private final List<ApiKeyRecord> records = new ArrayList<>();
        private long sequence;

        @Override
        public Optional<ApiKeyRecord> findByPrefix(String keyPrefix) {
            return records.stream().filter(record -> record.getKeyPrefix().equals(keyPrefix)).findFirst();
        }

        @Override
        public List<ApiKeyRecord> findAll() {
            return List.copyOf(records);
        }

        @Override
        public void insert(ApiKeyRecord record) {
            record.setId(++sequence);
            records.add(record);
        }

        @Override
        public void update(ApiKeyRecord record) {
            // 对象引用即存储记录，测试实现无需复制。
        }
    }
}
