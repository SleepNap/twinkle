package org.gms.httpapi.admin;

import org.gms.data.entity.Account;
import org.gms.data.entity.AccountAdminRole;
import org.gms.data.entity.AdminRole;
import org.gms.data.entity.AdminSession;
import org.gms.data.repo.AccountAdminRoleRepository;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AdminRoleRepository;
import org.gms.data.repo.AdminSessionRepository;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理员登录、会话签发与认证测试（内存仓库替身）。 */
class AdminSessionServiceTest {

    @Test
    void login_withSuperAdminRole_returnsToken() {
        Fixture f = new Fixture();
        f.account("alice", "secret");
        f.role(1L, "super_admin", "*");
        f.assign("alice", 1L);

        Optional<AdminSessionService.LoginResult> result = f.service.login("alice", "secret", "127.0.0.1");

        assertThat(result).isPresent();
        assertThat(result.get().token()).startsWith("twk_adm_");
        assertThat(result.get().principal().accountName()).isEqualTo("alice");
        assertThat(result.get().principal().permits("admin:read")).isTrue();
    }

    @Test
    void login_withoutRole_returnsEmpty() {
        Fixture f = new Fixture();
        f.account("bob", "secret");

        assertThat(f.service.login("bob", "secret", "127.0.0.1")).isEmpty();
    }

    @Test
    void login_wrongPassword_returnsEmpty() {
        Fixture f = new Fixture();
        f.account("carol", "secret");
        f.role(1L, "super_admin", "*");
        f.assign("carol", 1L);

        assertThat(f.service.login("carol", "wrong", "127.0.0.1")).isEmpty();
    }

    @Test
    void authenticate_validToken_returnsPrincipal() {
        Fixture f = new Fixture();
        f.account("dave", "secret");
        f.role(1L, "super_admin", "*");
        f.assign("dave", 1L);
        String token = f.service.login("dave", "secret", "127.0.0.1").orElseThrow().token();

        Optional<AdminPrincipal> principal = f.service.authenticate(token);

        assertThat(principal).isPresent();
        assertThat(principal.get().accountName()).isEqualTo("dave");
    }

    @Test
    void authenticate_revokedSession_returnsEmpty() {
        Fixture f = new Fixture();
        f.account("eve", "secret");
        f.role(1L, "super_admin", "*");
        f.assign("eve", 1L);
        String token = f.service.login("eve", "secret", "127.0.0.1").orElseThrow().token();

        f.service.logout(token);

        assertThat(f.service.authenticate(token)).isEmpty();
    }

    @Test
    void principal_permitsWildcard() {
        AdminPrincipal p = new AdminPrincipal(1L, "admin", 1L, java.util.Set.of(AdminPermission.ALL));
        assertThat(p.permits("admin.config:write")).isTrue();
        assertThat(p.permits("anything")).isTrue();
    }

    private static final class Fixture {
        final StubAccountRepo accounts = new StubAccountRepo();
        final StubRoleRepo roles = new StubRoleRepo();
        final StubAccountRoleRepo accountRoles = new StubAccountRoleRepo();
        final StubSessionRepo sessions = new StubSessionRepo();
        final AdminSessionService service = new AdminSessionService(accounts, roles, accountRoles,
                sessions, 86400L, new SecureRandom());

        void account(String name, String password) {
            Account a = new Account();
            a.setId((long) accounts.byName.size() + 1);
            a.setName(name);
            a.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
            a.setBanned(0);
            accounts.byName.put(name, a);
        }

        void role(Long id, String code, String permissions) {
            AdminRole r = new AdminRole();
            r.setId(id);
            r.setRoleCode(code);
            r.setPermissions(permissions);
            roles.byId.put(id, r);
            roles.byCode.put(code, r);
        }

        void assign(String accountName, Long roleId) {
            Account a = accounts.byName.get(accountName);
            AccountAdminRole relation = new AccountAdminRole();
            relation.setAccountId(a.getId());
            relation.setRoleId(roleId);
            accountRoles.relations.add(relation);
        }
    }

    private static final class StubAccountRepo implements AccountRepository {
        final Map<String, Account> byName = new HashMap<>();

        @Override
        public Optional<Account> findByName(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public Optional<Account> findById(Long id) {
            return byName.values().stream().filter(a -> id.equals(a.getId())).findFirst();
        }

        @Override
        public void insert(Account account) {
            byName.put(account.getName(), account);
        }

        @Override
        public void update(Account account) {
            byName.put(account.getName(), account);
        }

        @Override
        public List<Account> findByNameLike(String query, int limit) {
            return List.of();
        }
    }

    private static final class StubRoleRepo implements AdminRoleRepository {
        final Map<Long, AdminRole> byId = new HashMap<>();
        final Map<String, AdminRole> byCode = new HashMap<>();

        @Override
        public List<AdminRole> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public Optional<AdminRole> findByRoleCode(String roleCode) {
            return Optional.ofNullable(byCode.get(roleCode));
        }

        @Override
        public Optional<AdminRole> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public void insert(AdminRole role) {
            byId.put(role.getId(), role);
            byCode.put(role.getRoleCode(), role);
        }

        @Override
        public void update(AdminRole role) {
            byId.put(role.getId(), role);
            byCode.put(role.getRoleCode(), role);
        }
    }

    private static final class StubAccountRoleRepo implements AccountAdminRoleRepository {
        final List<AccountAdminRole> relations = new ArrayList<>();

        @Override
        public List<AccountAdminRole> findByAccountId(Long accountId) {
            return relations.stream().filter(r -> accountId.equals(r.getAccountId())).toList();
        }

        @Override
        public void insert(AccountAdminRole relation) {
            relations.add(relation);
        }

        @Override
        public void deleteByAccountId(Long accountId) {
            relations.removeIf(r -> accountId.equals(r.getAccountId()));
        }

        @Override
        public long count() {
            return relations.size();
        }
    }

    private static final class StubSessionRepo implements AdminSessionRepository {
        final Map<String, AdminSession> byPrefix = new HashMap<>();

        @Override
        public Optional<AdminSession> findByPrefix(String tokenPrefix) {
            return Optional.ofNullable(byPrefix.get(tokenPrefix));
        }

        @Override
        public void insert(AdminSession session) {
            if (session.getId() == null) {
                session.setId((long) byPrefix.size() + 1);
            }
            byPrefix.put(session.getTokenPrefix(), session);
        }

        @Override
        public void update(AdminSession session) {
            byPrefix.put(session.getTokenPrefix(), session);
        }
    }
}
