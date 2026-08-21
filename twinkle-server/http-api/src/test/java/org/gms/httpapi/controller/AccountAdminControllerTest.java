package org.gms.httpapi.controller;

import io.micronaut.http.HttpRequest;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAdminControllerTest {

    @Test
    void banDisconnectsCharactersAndClearsLoginState() {
        MemoryAccounts accounts = new MemoryAccounts(account(7L, "alice"));
        MemoryCharacters characters = new MemoryCharacters(character(71L, 7L));
        FakeAdmin admin = new FakeAdmin();
        AccountAdminController controller = new AccountAdminController(accounts, characters, admin);

        assertThat(controller.updateRestrictions(HttpRequest.PUT("/", ""), 7L,
                java.util.Map.of("banned", true, "banReason", "abuse")).code()).isEqualTo(200);

        assertThat(accounts.account.getBanned()).isEqualTo(1);
        assertThat(accounts.account.getBanReason()).isEqualTo("abuse");
        assertThat(accounts.account.getLoggedIn()).isZero();
        assertThat(admin.kicked).containsExactly(71L);
    }

    @Test
    void unbanAndMuteUpdatesRestrictionState() {
        Account account = account(7L, "alice");
        account.setBanned(1);
        account.setBanReason("old");
        MemoryAccounts accounts = new MemoryAccounts(account);
        AccountAdminController controller = new AccountAdminController(
                accounts, new MemoryCharacters(), new FakeAdmin());

        assertThat(controller.updateRestrictions(HttpRequest.PUT("/", ""), 7L,
                java.util.Map.of("banned", false, "muted", true)).code()).isEqualTo(200);

        assertThat(account.getBanned()).isZero();
        assertThat(account.getBanReason()).isEmpty();
        assertThat(account.getMute()).isEqualTo(1);
    }

    @Test
    void forceOfflineRepairsStaleLoginState() {
        Account account = account(7L, "alice");
        MemoryAccounts accounts = new MemoryAccounts(account);
        AccountAdminController controller = new AccountAdminController(
                accounts, new MemoryCharacters(character(71L, 7L)), new FakeAdmin());

        assertThat(controller.forceOffline(HttpRequest.POST("/", ""), 7L).code()).isEqualTo(200);
        assertThat(account.getLoggedIn()).isZero();
    }

    private static Account account(long id, String name) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setLoggedIn(1);
        account.setMute(0);
        return account;
    }

    private static Character character(long id, long accountId) {
        Character character = new Character();
        character.setId(id);
        character.setAccountId(accountId);
        character.setName("hero");
        return character;
    }

    private static final class MemoryAccounts implements AccountRepository {
        private final Account account;

        private MemoryAccounts(Account account) {
            this.account = account;
        }

        @Override
        public Optional<Account> findByName(String name) {
            return account.getName().equals(name) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<Account> findById(Long id) {
            return account.getId().equals(id) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public void insert(Account account) {
        }

        @Override
        public void update(Account account) {
        }

        @Override
        public List<Account> findByNameLike(String query, int limit) {
            return List.of(account);
        }
    }

    private static final class MemoryCharacters implements CharacterRepository {
        private final List<Character> characters;

        private MemoryCharacters(Character... characters) {
            this.characters = List.of(characters);
        }

        @Override
        public List<Character> findByAccount(int accountId, int world) {
            return characters;
        }

        @Override
        public Optional<Character> findById(long id) {
            return characters.stream().filter(character -> character.getId() == id).findFirst();
        }

        @Override
        public boolean existsByName(String name) {
            return false;
        }

        @Override
        public void insert(Character chr) {
        }

        @Override
        public void save(Character chr) {
        }
    }

    private static final class FakeAdmin implements AdminService {
        private final List<Long> kicked = new ArrayList<>();

        @Override
        public ChannelSummary onlineSummary() {
            return new ChannelSummary(0, 1, List.of());
        }

        @Override
        public boolean kick(long characterId) {
            kicked.add(characterId);
            return true;
        }

        @Override
        public int reloadScripts() {
            return 0;
        }

        @Override
        public void requestRestart() {
        }

        @Override
        public RestartCoordinator.Phase restartPhase() {
            return RestartCoordinator.Phase.RUNNING;
        }
    }
}
