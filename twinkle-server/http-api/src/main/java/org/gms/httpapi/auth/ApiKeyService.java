package org.gms.httpapi.auth;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.ApiKeyRecord;
import org.gms.data.repo.ApiKeyRepository;
import org.gms.httpapi.identity.ServerIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** API-key Credential 的签发、校验、禁用、吊销和轮换。 */
@Log4j2
public final class ApiKeyService {

    private static final String TOKEN_PREFIX = "twk_";
    private static final int LOOKUP_PREFIX_LENGTH = 12;
    private static final String OWNER_SUBJECT_ID = "subject_owner";
    private static final String OWNER_DISPLAY_NAME = "平台所有者";

    private final ApiKeyRepository repository;
    private final SecureRandom secureRandom;
    private final String bootstrapKey;
    private final ServerIdentity serverIdentity;

    public ApiKeyService(ApiKeyRepository repository, String bootstrapKey, ServerIdentity serverIdentity) {
        this(repository, bootstrapKey, serverIdentity, new SecureRandom());
    }

    public ApiKeyService(ApiKeyRepository repository, String bootstrapKey, ServerIdentity serverIdentity,
                         SecureRandom secureRandom) {
        this.repository = repository;
        this.bootstrapKey = bootstrapKey == null ? "" : bootstrapKey.trim();
        this.serverIdentity = serverIdentity;
        this.secureRandom = secureRandom;
        if (this.bootstrapKey.isBlank()) {
            log.warn("twish 能力面未配置 TWINKLE_API_BOOTSTRAP_KEY：公开契约可读，其余 /api/v1 请求均拒绝，直至配置初始管理密钥");
        } else if (this.bootstrapKey.length() < 32) {
            log.warn("TWINKLE_API_BOOTSTRAP_KEY 长度不足 32 字符，请尽快更换高熵密钥");
        }
    }

    /** 明文 token 只包含在返回值中；issuer 限制新 Credential 不得扩权或跨服。 */
    public IssuedKey issue(ApiPrincipal issuer, String displayName, Long ownerAccountId,
                           Set<String> requestedScopes, String expiresAt) {
        Set<String> scopes = normalizeScopes(requestedScopes);
        requireIssuerMayGrant(issuer, scopes);
        String normalizedName = normalizeDisplayName(displayName);
        String normalizedExpiry = normalizeExpiry(expiresAt, issuer.expiresAt());

        for (int attempt = 0; attempt < 3; attempt++) {
            String prefix = randomHex(LOOKUP_PREFIX_LENGTH / 2);
            if (repository.findByPrefix(prefix).isPresent()) {
                continue;
            }
            return persistNewKey(issuer, prefix, normalizedName, ownerAccountId, scopes,
                    normalizedExpiry, null);
        }
        throw new IllegalStateException("无法生成唯一 API-key 前缀");
    }

    public Optional<ApiPrincipal> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String candidate = token.trim();
        if (!bootstrapKey.isBlank() && constantTimeEquals(candidate, bootstrapKey)) {
            return Optional.of(bootstrapPrincipal());
        }
        Optional<String> prefix = extractPrefix(candidate);
        if (prefix.isEmpty()) {
            return Optional.empty();
        }
        return repository.findByPrefix(prefix.get())
                .filter(record -> isBlank(record.getDisabledAt()))
                .filter(record -> isBlank(record.getRevokedAt()))
                .filter(record -> !isExpired(record.getExpiresAt()))
                .filter(record -> constantTimeHashEquals(candidate, record.getSecretHash()))
                .map(ApiKeyService::principalOf);
    }

    public void markUsed(ApiPrincipal principal) {
        if (principal.keyId() == null) {
            return;
        }
        repository.findByPrefix(principal.keyPrefix()).ifPresent(record -> {
            record.setLastUsedAt(Instant.now().toString());
            repository.update(record);
        });
    }

    public boolean setDisabled(ApiPrincipal issuer, String keyPrefix, boolean disabled) {
        Optional<ApiKeyRecord> found = manageableRecord(issuer, keyPrefix);
        if (found.isEmpty()) {
            return false;
        }
        ApiKeyRecord record = found.get();
        record.setDisabledAt(disabled ? Instant.now().toString() : null);
        record.setPermissionVersion(newPublicId("perm"));
        repository.update(record);
        return true;
    }

    public boolean revoke(ApiPrincipal issuer, String keyPrefix) {
        Optional<ApiKeyRecord> found = manageableRecord(issuer, keyPrefix);
        if (found.isEmpty()) {
            return false;
        }
        ApiKeyRecord record = found.get();
        if (isBlank(record.getRevokedAt())) {
            record.setRevokedAt(Instant.now().toString());
            record.setPermissionVersion(newPublicId("perm"));
            repository.update(record);
        }
        return true;
    }

    /** Replaces the credential scope set and rotates permissionVersion immediately. */
    public Optional<KeySummary> updateScopes(ApiPrincipal issuer, String keyPrefix,
                                             Set<String> requestedScopes) {
        Optional<ApiKeyRecord> found = manageableRecord(issuer, keyPrefix)
                .filter(record -> isBlank(record.getRevokedAt()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Set<String> scopes = normalizeScopes(requestedScopes);
        requireIssuerMayGrant(issuer, scopes);
        ApiKeyRecord record = found.get();
        record.setScopes(String.join(",", scopes));
        record.setPermissionVersion(newPublicId("perm"));
        repository.update(record);
        return Optional.of(summary(record));
    }

    /** 生成等权新 Credential 后立即吊销旧 Credential；新秘密仍只返回一次。 */
    public Optional<IssuedKey> rotate(ApiPrincipal issuer, String keyPrefix) {
        Optional<ApiKeyRecord> found = manageableRecord(issuer, keyPrefix)
                .filter(record -> isBlank(record.getRevokedAt()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyRecord old = found.get();
        Set<String> scopes = parseScopes(old.getScopes());
        requireIssuerMayGrant(issuer, scopes);
        String newPrefix = randomHex(LOOKUP_PREFIX_LENGTH / 2);
        IssuedKey issued = persistNewKey(issuer, newPrefix, old.getDisplayName(),
                old.getOwnerAccountId(), scopes, old.getExpiresAt(), old.getKeyPrefix());
        old.setRevokedAt(Instant.now().toString());
        old.setPermissionVersion(newPublicId("perm"));
        repository.update(old);
        return Optional.of(issued);
    }

    public List<KeySummary> list(ApiPrincipal issuer) {
        return repository.findAll().stream()
                .filter(record -> issuer.scopes().contains("*")
                        || issuer.subjectId().equals(record.getSubjectId()))
                .map(ApiKeyService::summary)
                .toList();
    }

    private static KeySummary summary(ApiKeyRecord record) {
        return new KeySummary(record.getId(), record.getCredentialId(),
                record.getKeyPrefix(), record.getDisplayName(), record.getSubjectId(),
                record.getOwnerAccountId(), parseScopes(record.getScopes()), record.getServerId(),
                record.getCreatedAt(), record.getExpiresAt(), record.getDisabledAt(),
                record.getRevokedAt(), record.getRotatedFromPrefix(), record.getLastUsedAt());
    }

    public ApiPrincipal bootstrapPrincipal() {
        return new ApiPrincipal(null, "cred_bootstrap", "bootstrap", OWNER_SUBJECT_ID,
                OWNER_DISPLAY_NAME, "owner bootstrap", Set.of("*"), serverIdentity.serverId(),
                null, "perm_bootstrap");
    }

    private IssuedKey persistNewKey(ApiPrincipal issuer, String prefix, String displayName,
                                    Long ownerAccountId, Set<String> scopes, String expiresAt,
                                    String rotatedFromPrefix) {
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String token = TOKEN_PREFIX + prefix + "_" + secret;
        String now = Instant.now().toString();
        String credentialId = newPublicId("cred");
        String permissionVersion = newPublicId("perm");

        ApiKeyRecord record = new ApiKeyRecord();
        record.setCredentialId(credentialId);
        record.setKeyPrefix(prefix);
        record.setSecretHash(sha256Hex(token));
        record.setSubjectId(issuer.subjectId());
        record.setSubjectDisplayName(issuer.subjectDisplayName());
        record.setCreatedBySubjectId(issuer.subjectId());
        record.setServerId(serverIdentity.serverId());
        record.setOwnerAccountId(ownerAccountId);
        record.setDisplayName(displayName);
        record.setScopes(String.join(",", scopes));
        record.setExpiresAt(expiresAt);
        record.setRotatedFromPrefix(rotatedFromPrefix);
        record.setPermissionVersion(permissionVersion);
        repository.insert(record);
        return new IssuedKey(record.getId(), credentialId, prefix, token, displayName,
                issuer.subjectId(), ownerAccountId, scopes, serverIdentity.serverId(), now,
                expiresAt, rotatedFromPrefix);
    }

    private Optional<ApiKeyRecord> manageableRecord(ApiPrincipal issuer, String keyPrefix) {
        return repository.findByPrefix(keyPrefix)
                .filter(record -> issuer.scopes().contains("*")
                        || issuer.subjectId().equals(record.getSubjectId()));
    }

    private void requireIssuerMayGrant(ApiPrincipal issuer, Set<String> scopes) {
        if (!serverIdentity.serverId().equals(issuer.serverId())) {
            throw new IllegalArgumentException("Credential 无权为其他服务器签发 key");
        }
        if (!issuer.scopes().contains("*") && !issuer.scopes().containsAll(scopes)) {
            throw new IllegalArgumentException("新 key 的 scope 不能超过签发者");
        }
    }

    private static ApiPrincipal principalOf(ApiKeyRecord record) {
        return new ApiPrincipal(record.getId(), record.getCredentialId(), record.getKeyPrefix(),
                record.getSubjectId(), record.getSubjectDisplayName(), record.getDisplayName(),
                parseScopes(record.getScopes()), record.getServerId(), record.getExpiresAt(),
                record.getPermissionVersion());
    }

    private static String normalizeDisplayName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        if (normalized.isBlank() || normalized.length() > 128) {
            throw new IllegalArgumentException("displayName 必须为 1-128 个字符");
        }
        return normalized;
    }

    private static Set<String> normalizeScopes(Set<String> requestedScopes) {
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            throw new IllegalArgumentException("scopes 不能为空");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String scope : requestedScopes) {
            String value = scope == null ? "" : scope.trim();
            if (!ApiScopes.SUPPORTED.contains(value)) {
                throw new IllegalArgumentException("不支持的 scope: " + value);
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeExpiry(String expiresAt, String issuerExpiry) {
        if (expiresAt == null || expiresAt.isBlank()) {
            if (issuerExpiry != null) {
                throw new IllegalArgumentException("新 key 的有效期不能超过签发者");
            }
            return null;
        }
        try {
            Instant parsed = Instant.parse(expiresAt.trim());
            if (!parsed.isAfter(Instant.now())) {
                throw new IllegalArgumentException("expiresAt 必须晚于当前时间");
            }
            if (issuerExpiry != null && parsed.isAfter(Instant.parse(issuerExpiry))) {
                throw new IllegalArgumentException("新 key 的有效期不能超过签发者");
            }
            return parsed.toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt 必须是 ISO-8601 UTC 时间", e);
        }
    }

    private static boolean isExpired(String expiresAt) {
        if (isBlank(expiresAt)) {
            return false;
        }
        try {
            return !Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private static Optional<String> extractPrefix(String token) {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        int separator = token.indexOf('_', TOKEN_PREFIX.length());
        if (separator != TOKEN_PREFIX.length() + LOOKUP_PREFIX_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(token.substring(TOKEN_PREFIX.length(), separator));
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static String newPublicId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Set<String> parseScopes(String value) {
        if (isBlank(value)) {
            return Set.of();
        }
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                scopes.add(part.trim());
            }
        }
        return Set.copyOf(scopes);
    }

    private static boolean constantTimeHashEquals(String token, String storedHash) {
        if (storedHash == null || storedHash.length() != 64) {
            return false;
        }
        try {
            byte[] expected = HexFormat.of().parseHex(storedHash);
            byte[] actual = HexFormat.of().parseHex(sha256Hex(token));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record IssuedKey(Long id, String credentialId, String keyPrefix, String token,
                            String displayName, String subjectId, Long ownerAccountId,
                            Set<String> scopes, String serverId, String createdAt,
                            String expiresAt, String rotatedFromPrefix) {
    }

    public record KeySummary(Long id, String credentialId, String keyPrefix, String displayName,
                             String subjectId, Long ownerAccountId, Set<String> scopes,
                             String serverId, String createdAt, String expiresAt,
                             String disabledAt, String revokedAt, String rotatedFromPrefix,
                             String lastUsedAt) {
    }
}
