package org.gms.httpapi.admin;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.data.entity.AccountAdminRole;
import org.gms.data.entity.AdminSession;
import org.gms.data.repo.AccountAdminRoleRepository;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AdminRoleRepository;
import org.gms.data.repo.AdminSessionRepository;
import org.gms.i18n.I18n;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Web 控制台管理员登录、会话签发与认证（强鉴权）。
 *
 * <p>管理员身份挂在 {@code account_records} 账号（复用游戏登录 BCrypt 密码），登录后签发 DB
 * session token（只存 SHA-256 摘要）。权限点实时从角色表聚合，角色变更即时生效。
 */
@Log4j2
public final class AdminSessionService {

    private static final String TOKEN_PREFIX = "twk_adm_";
    private static final int LOOKUP_PREFIX_LENGTH = 12;
    private static final String SUPER_ADMIN_ROLE = "super_admin";

    private final AccountRepository accountRepository;
    private final AdminRoleRepository roleRepository;
    private final AccountAdminRoleRepository accountRoleRepository;
    private final AdminSessionRepository sessionRepository;
    private final SecureRandom secureRandom;
    private final long sessionTtlSeconds;

    public AdminSessionService(AccountRepository accountRepository,
                               AdminRoleRepository roleRepository,
                               AccountAdminRoleRepository accountRoleRepository,
                               AdminSessionRepository sessionRepository,
                               long sessionTtlSeconds) {
        this(accountRepository, roleRepository, accountRoleRepository, sessionRepository,
                sessionTtlSeconds, new SecureRandom());
    }

    public AdminSessionService(AccountRepository accountRepository,
                               AdminRoleRepository roleRepository,
                               AccountAdminRoleRepository accountRoleRepository,
                               AdminSessionRepository sessionRepository,
                               long sessionTtlSeconds, SecureRandom secureRandom) {
        this.accountRepository = accountRepository;
        this.roleRepository = roleRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.sessionRepository = sessionRepository;
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.secureRandom = secureRandom;
    }

    public record LoginResult(AdminPrincipal principal, String token) {
    }

    /** 账号 + 密码登录；账号无任何管理员角色或密码错、被封禁时返回 empty。 */
    public Optional<LoginResult> login(String name, String password, String remoteAddress) {
        String normalized = name == null ? "" : name.trim();
        Optional<Account> found = accountRepository.findByName(normalized);
        if (found.isEmpty()) {
            log.warn(I18n.message("log.admin.login_failed"), normalized);
            return Optional.empty();
        }
        Account account = found.get();
        if (account.getBanned() == 1) {
            log.warn(I18n.message("log.admin.login_banned"), normalized);
            return Optional.empty();
        }
        String stored = account.getPassword();
        if (stored == null || stored.isEmpty() || !BCrypt.checkpw(password, stored)) {
            log.warn(I18n.message("log.admin.login_failed"), normalized);
            return Optional.empty();
        }
        Set<String> permissions = permissionsOf(account.getId());
        if (permissions.isEmpty()) {
            log.warn(I18n.message("log.admin.login_failed"), normalized);
            return Optional.empty();
        }
        IssuedSession issued = persistSession(account.getId(), remoteAddress);
        AdminPrincipal principal = new AdminPrincipal(account.getId(), account.getName(),
                issued.sessionId(), permissions);
        log.info(I18n.message("log.admin.login_success"), normalized);
        return Optional.of(new LoginResult(principal, issued.token()));
    }

    /** 校验 session token，返回调用者投影；无效/过期/吊销返回 empty。 */
    public Optional<AdminPrincipal> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String candidate = token.trim();
        Optional<String> prefix = extractPrefix(candidate);
        if (prefix.isEmpty()) {
            return Optional.empty();
        }
        Optional<AdminSession> found = sessionRepository.findByPrefix(prefix.get());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AdminSession session = found.get();
        if (!isBlank(session.getRevokedAt()) || isExpired(session.getExpiresAt())
                || !constantTimeHashEquals(candidate, session.getTokenHash())) {
            return Optional.empty();
        }
        session.setLastUsedAt(Instant.now().toString());
        sessionRepository.update(session);
        return accountRepository.findById(session.getAccountId())
                .map(account -> new AdminPrincipal(account.getId(), account.getName(),
                        session.getId(), permissionsOf(account.getId())));
    }

    /** 登出：吊销会话。 */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        extractPrefix(token.trim()).ifPresent(prefix ->
                sessionRepository.findByPrefix(prefix).ifPresent(session -> {
                    if (isBlank(session.getRevokedAt())) {
                        session.setRevokedAt(Instant.now().toString());
                        sessionRepository.update(session);
                    }
                }));
    }

    /** 实时聚合某账号的权限点（角色变更即时生效）。 */
    public Set<String> permissionsOf(Long accountId) {
        Set<String> permissions = new HashSet<>();
        for (AccountAdminRole relation : accountRoleRepository.findByAccountId(accountId)) {
            roleRepository.findById(relation.getRoleId()).ifPresent(role ->
                    permissions.addAll(parsePermissions(role.getPermissions())));
        }
        return permissions;
    }

    /** bootstrap 首个管理员：配置了账号+密码则创建/补授 super_admin；否则无管理员时告警。 */
    public void bootstrapAdmin(String accountName, String password) {
        if (!isBlank(accountName) && !isBlank(password)) {
            Account account = accountRepository.findByName(accountName.trim())
                    .orElseGet(() -> createAdminAccount(accountName.trim(), password));
            grantRole(account.getId(), SUPER_ADMIN_ROLE);
            log.info(I18n.message("log.admin.bootstrap_ready"), accountName);
            return;
        }
        if (accountRoleRepository.count() == 0) {
            log.warn(I18n.message("log.admin.bootstrap_missing"));
        }
    }

    private Account createAdminAccount(String name, String password) {
        Account account = new Account();
        account.setName(name);
        account.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        account.setWebAdmin(1);
        accountRepository.insert(account);
        return account;
    }

    private void grantRole(Long accountId, String roleCode) {
        roleRepository.findByRoleCode(roleCode).ifPresent(role -> {
            boolean already = accountRoleRepository.findByAccountId(accountId).stream()
                    .anyMatch(relation -> role.getId().equals(relation.getRoleId()));
            if (!already) {
                AccountAdminRole relation = new AccountAdminRole();
                relation.setAccountId(accountId);
                relation.setRoleId(role.getId());
                accountRoleRepository.insert(relation);
            }
        });
    }

    private IssuedSession persistSession(Long accountId, String remoteAddress) {
        byte[] secretBytes = new byte[32];
        secureRandom.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String prefix = randomHex(LOOKUP_PREFIX_LENGTH / 2);
        String token = TOKEN_PREFIX + prefix + "_" + secret;
        AdminSession session = new AdminSession();
        session.setTokenPrefix(prefix);
        session.setTokenHash(sha256Hex(token));
        session.setAccountId(accountId);
        session.setExpiresAt(Instant.now().plusSeconds(sessionTtlSeconds).toString());
        session.setRemoteAddress(remoteAddress == null ? "" : remoteAddress);
        sessionRepository.insert(session);
        return new IssuedSession(session.getId(), token);
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

    private static Set<String> parsePermissions(String value) {
        if (isBlank(value)) {
            return Set.of();
        }
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                permissions.add(part.trim());
            }
        }
        return Set.copyOf(permissions);
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        return HexFormat.of().formatHex(value);
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

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(I18n.message("error.crypto.algorithm_missing", "SHA-256"), e);
        }
    }

    private static boolean isExpired(String expiresAt) {
        if (isBlank(expiresAt)) {
            return false;
        }
        try {
            return !Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record IssuedSession(Long sessionId, String token) {
    }
}
