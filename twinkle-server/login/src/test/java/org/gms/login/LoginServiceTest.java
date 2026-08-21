package org.gms.login;

import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录校验逻辑测试（架构 login：账号 + 密码 + 封禁校验，红线 8 banned=1 语义）。
 */
class LoginServiceTest {

    /** 内存仓库替身（真实实现走 MyBatis-Flex，接口化便于聚焦校验逻辑）。 */
    private static final class StubAccountRepository implements AccountRepository {
        final Map<String, Account> byName = new HashMap<>();

        @Override
        public Optional<Account> findByName(String name) {
            return Optional.ofNullable(byName.get(name));
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
        public Optional<Account> findById(Long id) {
            return byName.values().stream().filter(account -> id.equals(account.getId())).findFirst();
        }

        @Override
        public List<Account> findByNameLike(String query, int limit) {
            return List.of();
        }
    }

    private static final class StubCharacterRepository implements CharacterRepository {
        final List<Character> characters = new java.util.ArrayList<>();

        @Override
        public List<Character> findByAccount(int accountId, int world) {
            return characters;
        }

        @Override
        public java.util.Optional<Character> findById(long id) {
            return characters.stream().filter(c -> c.getId() != null && c.getId() == id).findFirst();
        }

        @Override
        public boolean existsByName(String name) {
            return characters.stream().anyMatch(c -> name.equals(c.getName()));
        }

        @Override
        public void insert(Character chr) {
            characters.add(chr);
        }

        @Override
        public void save(Character chr) {
            // 测试桩：M5 起 CharacterRepository 新增 save（L4 增量 FLUSH），登录测试不落库
        }
    }

    private static Account account(String name, String rawPassword, int banned) {
        Account a = new Account();
        a.setId(1L);
        a.setName(name);
        a.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        a.setBanned(banned);
        return a;
    }

    @Test
    void authenticate_successWithBcryptMatch() {
        StubAccountRepository repo = new StubAccountRepository();
        repo.byName.put("alice", account("alice", "secret", 0));
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        LoginService.LoginResult result = service.authenticate("alice", "secret");

        assertThat(result.errorCode()).isZero();
        assertThat(result.account()).isNotNull();
        assertThat(result.account().getName()).isEqualTo("alice");
    }

    @Test
    void authenticate_wrongPassword_returns4() {
        StubAccountRepository repo = new StubAccountRepository();
        repo.byName.put("alice", account("alice", "secret", 0));
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        assertThat(service.authenticate("alice", "wrong").errorCode()).isEqualTo(4);
    }

    @Test
    public void authenticate_validTemporaryPassword_succeedsOnceWithoutChangingPlayerPassword() {
        StubAccountRepository repo = new StubAccountRepository();
        Account account = account("alice", "secret", 0);
        account.setTemporaryPasswordHash(BCrypt.hashpw("GM-temp-42", BCrypt.gensalt()));
        account.setTemporaryPasswordExpiresAt(Instant.now().plusSeconds(300).toString());
        repo.byName.put("alice", account);
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        assertThat(service.authenticate("alice", "GM-temp-42").errorCode()).isZero();
        assertThat(account.getTemporaryPasswordHash()).isEmpty();
        assertThat(account.getTemporaryPasswordExpiresAt()).isEmpty();
        assertThat(service.authenticate("alice", "GM-temp-42").errorCode()).isEqualTo(4);
        assertThat(service.authenticate("alice", "secret").errorCode()).isZero();
    }

    @Test
    public void authenticate_expiredTemporaryPassword_returns4() {
        StubAccountRepository repo = new StubAccountRepository();
        Account account = account("alice", "secret", 0);
        account.setTemporaryPasswordHash(BCrypt.hashpw("GM-temp-42", BCrypt.gensalt()));
        account.setTemporaryPasswordExpiresAt(Instant.now().minusSeconds(1).toString());
        repo.byName.put("alice", account);
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        assertThat(service.authenticate("alice", "GM-temp-42").errorCode()).isEqualTo(4);
        assertThat(account.getTemporaryPasswordHash()).isNotEmpty();
    }

    @Test
    void authenticate_unknownAccount_returns5() {
        StubAccountRepository repo = new StubAccountRepository();
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        assertThat(service.authenticate("nobody", "x").errorCode()).isEqualTo(5);
    }

    @Test
    void authenticate_banned_returns3() {
        StubAccountRepository repo = new StubAccountRepository();
        repo.byName.put("bob", account("bob", "pw", 1)); // banned=1 明确封禁（红线 8）
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        assertThat(service.authenticate("bob", "pw").errorCode()).isEqualTo(3);
    }

    @Test
    void authenticate_missingStoredHash_returns4() {
        StubAccountRepository repo = new StubAccountRepository();
        Account a = account("carol", "pw", 0);
        a.setPassword("");
        repo.byName.put("carol", a);
        LoginService service = new LoginService(repo, new StubCharacterRepository());

        assertThat(service.authenticate("carol", "pw").errorCode()).isEqualTo(4);
    }

    @Test
    void charactersFor_delegatesToRepository() {
        StubAccountRepository accRepo = new StubAccountRepository();
        StubCharacterRepository charRepo = new StubCharacterRepository();
        charRepo.characters.add(new Character());
        LoginService service = new LoginService(accRepo, charRepo);

        assertThat(service.charactersFor(1L, 0)).hasSize(1);
    }
}
