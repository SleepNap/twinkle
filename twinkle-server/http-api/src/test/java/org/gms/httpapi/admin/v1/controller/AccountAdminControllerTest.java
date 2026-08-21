package org.gms.httpapi.admin.v1.controller;

import io.micronaut.http.HttpRequest;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AccountDeletionRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAdminControllerTest {

    @Test
    void createAccountHashesPasswordAndAppliesGameDefaults() {
        MemoryAccounts accounts = new MemoryAccounts();
        AccountAdminController controller = controller(
                accounts, new MemoryCharacters(), new FakeAdmin());
        HttpRequest<?> request = HttpRequest.POST("/", "");

        var response = controller.create(request,
                java.util.Map.of("name", "new_player", "password", "secret-123"));

        assertThat(response.code()).isEqualTo(201);
        assertThat(accounts.account).isNotNull();
        assertThat(accounts.account.getName()).isEqualTo("new_player");
        assertThat(accounts.account.getPassword()).isNotEqualTo("secret-123");
        assertThat(BCrypt.checkpw("secret-123", accounts.account.getPassword())).isTrue();
        assertThat(accounts.account.getCharacterSlots()).isEqualTo(3);
        assertThat(accounts.account.getGender()).isZero();
        assertThat(accounts.account.getLanguage()).isEqualTo(3);
        assertThat(accounts.account.getTos()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> responseBody =
                (java.util.Map<String, Object>) response.body();
        assertThat(responseBody).doesNotContainKeys("password", "temporaryPasswordHash");
        assertThat(request.getAttribute("twinkle.admin.after-summary", String.class).orElseThrow())
                .contains("accountId=99", "accountName=new_player", "created=true")
                .doesNotContain("secret-123", accounts.account.getPassword());
    }

    @Test
    void createAccountRejectsDuplicateNameAndInvalidInput() {
        AccountAdminController controller = controller(
                new MemoryAccounts(account(7L, "alice")), new MemoryCharacters(), new FakeAdmin());

        assertThat(controller.create(HttpRequest.POST("/", ""),
                java.util.Map.of("name", "alice", "password", "secret-123")).code()).isEqualTo(409);
        assertThat(controller.create(HttpRequest.POST("/", ""),
                java.util.Map.of("name", "a!", "password", "secret-123")).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""),
                java.util.Map.of("name", "account_name_14", "password", "secret-123")).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""),
                java.util.Map.of("name", "valid_name", "password", "short")).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""),
                java.util.Map.of("name", "valid_name", "password", "1234567890123")).code()).isEqualTo(400);
    }

    @Test
    void createAccountPersistsOptionalProfileAndInitialValues() {
        MemoryAccounts accounts = new MemoryAccounts();
        AccountAdminController controller = controller(
                accounts, new MemoryCharacters(), new FakeAdmin());

        var response = controller.create(HttpRequest.POST("/", ""), java.util.Map.ofEntries(
                java.util.Map.entry("name", "detailed_user"),
                java.util.Map.entry("password", "secret-123"),
                java.util.Map.entry("nick", "小明"),
                java.util.Map.entry("email", "player@example.com"),
                java.util.Map.entry("birthday", "2001-02-03"),
                java.util.Map.entry("pin", "1234"),
                java.util.Map.entry("pic", "567890"),
                java.util.Map.entry("characterSlots", 6),
                java.util.Map.entry("gender", 1),
                java.util.Map.entry("language", 3),
                java.util.Map.entry("tosAccepted", true),
                java.util.Map.entry("nxCredit", 1000),
                java.util.Map.entry("maplePoint", 200),
                java.util.Map.entry("nxPrepaid", 300),
                java.util.Map.entry("rewardPoints", 40),
                java.util.Map.entry("votePoints", 5)));

        assertThat(response.code()).isEqualTo(201);
        assertThat(accounts.account.getNick()).isEqualTo("小明");
        assertThat(accounts.account.getEmail()).isEqualTo("player@example.com");
        assertThat(accounts.account.getBirthday()).isEqualTo("2001-02-03");
        assertThat(accounts.account.getPin()).isEqualTo("1234");
        assertThat(accounts.account.getPic()).isEqualTo("567890");
        assertThat(accounts.account.getCharacterSlots()).isEqualTo(6);
        assertThat(accounts.account.getGender()).isEqualTo(1);
        assertThat(accounts.account.getTos()).isEqualTo(1);
        assertThat(accounts.account.getNxCredit()).isEqualTo(1000);
        assertThat(accounts.account.getMaplePoint()).isEqualTo(200);
        assertThat(accounts.account.getNxPrepaid()).isEqualTo(300);
        assertThat(accounts.account.getRewardPoints()).isEqualTo(40);
        assertThat(accounts.account.getVotePoints()).isEqualTo(5);
    }

    @Test
    void createAccountRejectsInvalidOptionalProfile() {
        AccountAdminController controller = controller(
                new MemoryAccounts(), new MemoryCharacters(), new FakeAdmin());

        assertThat(controller.create(HttpRequest.POST("/", ""), java.util.Map.of(
                "name", "valid_user", "password", "secret-123",
                "email", "not-an-email")).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""), java.util.Map.of(
                "name", "valid_user", "password", "secret-123",
                "characterSlots", 0)).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""), java.util.Map.of(
                "name", "valid_user", "password", "secret-123",
                "nxCredit", -1)).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""), java.util.Map.of(
                "name", "valid_user", "password", "secret-123",
                "birthday", "1899-12-31")).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""), java.util.Map.of(
                "name", "valid_user", "password", "secret-123",
                "language", 4)).code()).isEqualTo(400);
        assertThat(controller.create(HttpRequest.POST("/", ""), java.util.Map.of(
                "name", "valid_user", "password", "secret-123",
                "pin", "12345")).code()).isEqualTo(400);
    }

    @Test
    void banDisconnectsCharactersAndClearsLoginState() {
        MemoryAccounts accounts = new MemoryAccounts(account(7L, "alice"));
        MemoryCharacters characters = new MemoryCharacters(character(71L, 7L));
        FakeAdmin admin = new FakeAdmin();
        AccountAdminController controller = controller(accounts, characters, admin);

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
        AccountAdminController controller = controller(
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
        AccountAdminController controller = controller(
                accounts, new MemoryCharacters(character(71L, 7L)), new FakeAdmin());

        assertThat(controller.forceOffline(HttpRequest.POST("/", ""), 7L).code()).isEqualTo(200);
        assertThat(account.getLoggedIn()).isZero();
    }

    @Test
    void updateAccountChangesEditableProfileAndHonorsPasswordBoundary() {
        Account account = account(7L, "alice");
        account.setPassword(BCrypt.hashpw("oldpass", BCrypt.gensalt()));
        MemoryAccounts accounts = new MemoryAccounts(account);
        AccountAdminController controller = controller(accounts, new MemoryCharacters(), new FakeAdmin());

        var response = controller.update(HttpRequest.PUT("/", ""), 7L, java.util.Map.of(
                "password", "123456789012",
                "nick", "新昵称",
                "language", 2,
                "characterSlots", 15));

        assertThat(response.code()).isEqualTo(200);
        assertThat(account.getNick()).isEqualTo("新昵称");
        assertThat(account.getLanguage()).isEqualTo(2);
        assertThat(account.getCharacterSlots()).isEqualTo(15);
        assertThat(BCrypt.checkpw("123456789012", account.getPassword())).isTrue();
        assertThat(controller.update(HttpRequest.PUT("/", ""), 7L,
                java.util.Map.of("password", "1234567890123")).code()).isEqualTo(400);
    }

    @Test
    void deleteAccountDisconnectsCharactersAndDelegatesCascade() {
        MemoryAccounts accounts = new MemoryAccounts(account(7L, "alice"));
        MemoryCharacters characters = new MemoryCharacters(character(71L, 7L), character(72L, 7L));
        MemoryDeletion deletion = new MemoryDeletion();
        FakeAdmin admin = new FakeAdmin();
        AccountAdminController controller = new AccountAdminController(accounts, deletion, characters, admin);
        HttpRequest<?> request = HttpRequest.DELETE("/", "");

        var response = controller.delete(request, 7L);

        assertThat(response.code()).isEqualTo(200);
        assertThat(admin.kicked).containsExactly(71L, 72L);
        assertThat(deletion.deletedAccountId).isEqualTo(7L);
        assertThat(request.getAttribute("twinkle.admin.after-summary", String.class).orElseThrow())
                .contains("deleted=true", "characters=2", "relatedRows=9");
    }

    @Test
    public void generateTemporaryPasswordStoresOnlyHashAndKeepsPlayerPassword() {
        Account account = account(7L, "alice");
        account.setPassword(BCrypt.hashpw("player-secret", BCrypt.gensalt()));
        String playerPasswordHash = account.getPassword();
        MemoryAccounts accounts = new MemoryAccounts(account);
        AccountAdminController controller = controller(
                accounts, new MemoryCharacters(), new FakeAdmin());

        HttpRequest<?> request = HttpRequest.POST("/", "");
        var response = controller.generateTemporaryPassword(
                request, 7L, java.util.Map.of("durationMinutes", 15));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.body();
        String temporaryPassword = (String) body.get("temporaryPassword");

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.getHeaders().get("Cache-Control")).isEqualTo("no-store");
        assertThat(temporaryPassword).hasSize(12).doesNotContain("player-secret");
        assertThat(account.getTemporaryPasswordHash()).isNotEqualTo(temporaryPassword);
        assertThat(BCrypt.checkpw(temporaryPassword, account.getTemporaryPasswordHash())).isTrue();
        assertThat(Instant.parse(account.getTemporaryPasswordExpiresAt())).isAfter(Instant.now());
        assertThat(account.getPassword()).isEqualTo(playerPasswordHash);
        assertThat(request.getAttribute("twinkle.admin.after-summary", String.class).orElseThrow())
                .contains("accountId=7", "temporaryPasswordActive=true")
                .doesNotContain(temporaryPassword, account.getTemporaryPasswordHash());
    }

    @Test
    public void generateTemporaryPasswordRejectsUnsafeDuration() {
        AccountAdminController controller = controller(
                new MemoryAccounts(account(7L, "alice")), new MemoryCharacters(), new FakeAdmin());

        assertThat(controller.generateTemporaryPassword(
                HttpRequest.POST("/", ""), 7L, java.util.Map.of("durationMinutes", 121)).code())
                .isEqualTo(400);
    }

    private static Account account(long id, String name) {
        Account account = new Account();
        account.setId(id);
        account.setName(name);
        account.setLoggedIn(1);
        account.setMute(0);
        return account;
    }

    private static AccountAdminController controller(AccountRepository accounts,
                                                     CharacterRepository characters,
                                                     AdminService admin) {
        return new AccountAdminController(accounts, new MemoryDeletion(), characters, admin);
    }

    private static Character character(long id, long accountId) {
        Character character = new Character();
        character.setId(id);
        character.setAccountId(accountId);
        character.setName("hero");
        return character;
    }

    private static final class MemoryAccounts implements AccountRepository {
        private Account account;

        private MemoryAccounts() {
        }

        private MemoryAccounts(Account account) {
            this.account = account;
        }

        @Override
        public Optional<Account> findByName(String name) {
            return account != null && account.getName().equals(name) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<Account> findById(Long id) {
            return account != null && account.getId().equals(id) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public void insert(Account account) {
            if (account.getId() == null) {
                account.setId(99L);
            }
            this.account = account;
        }

        @Override
        public void update(Account account) {
        }

        @Override
        public List<Account> findByNameLike(String query, int limit) {
            return account == null ? List.of() : List.of(account);
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

    private static final class MemoryDeletion implements AccountDeletionRepository {
        private long deletedAccountId;

        @Override
        public DeletionResult deleteByAccountId(long accountId) {
            deletedAccountId = accountId;
            return new DeletionResult(2, 9);
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
        public WzReloadResult reloadWz() {
            return new WzReloadResult(2, java.util.Map.of(), java.util.Map.of());
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
