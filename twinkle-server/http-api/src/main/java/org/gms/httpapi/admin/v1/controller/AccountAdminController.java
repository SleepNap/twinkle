package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AccountDeletionRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.httpapi.admin.AdminAuthFilter;
import org.gms.service.admin.AdminService;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Web 控制台账号管理：分页检索、角色快照、封禁/禁言与强制下线。 */
@Controller(ApiRoutes.ADMIN_V1 + "/accounts")
@Produces(MediaType.APPLICATION_JSON)
public final class AccountAdminController {

    private static final int DEFAULT_TEMPORARY_PASSWORD_MINUTES = 30;
    private static final int MIN_TEMPORARY_PASSWORD_MINUTES = 5;
    private static final int MAX_TEMPORARY_PASSWORD_MINUTES = 120;
    private static final int TEMPORARY_PASSWORD_LENGTH = 12;
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 12;
    private static final Pattern ACCOUNT_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,13}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final char[] TEMPORARY_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AccountRepository accountRepository;
    private final AccountDeletionRepository accountDeletionRepository;
    private final CharacterRepository characterRepository;
    private final AdminService adminService;

    public AccountAdminController(AccountRepository accountRepository,
                                  AccountDeletionRepository accountDeletionRepository,
                                  CharacterRepository characterRepository,
                                  AdminService adminService) {
        this.accountRepository = accountRepository;
        this.accountDeletionRepository = accountDeletionRepository;
        this.characterRepository = characterRepository;
        this.adminService = adminService;
    }

    /** 按账号名分页搜索；status=all|active|banned。 */
    @Get
    public HttpResponse<?> list(@QueryValue(defaultValue = "") String query,
                                @QueryValue(defaultValue = "all") String status,
                                @QueryValue(defaultValue = "0") int offset,
                                @QueryValue(defaultValue = "20") int limit) {
        String normalizedStatus = status == null ? "all" : status.trim().toLowerCase();
        if (!Set.of("all", "active", "banned").contains(normalizedStatus)) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_status"));
        }
        Boolean banned = switch (normalizedStatus) {
            case "active" -> false;
            case "banned" -> true;
            default -> null;
        };
        AccountRepository.AccountPage page = accountRepository.findPage(query, banned,
                Math.max(0, offset), Math.max(1, Math.min(100, limit)));
        return HttpResponse.ok(Map.of(
                "total", page.total(),
                "offset", page.offset(),
                "limit", page.limit(),
                "accounts", page.records().stream().map(this::accountMap).toList()));
    }

    /** 创建可直接登录游戏的新账号；密码仅以 BCrypt 摘要落库。 */
    @Post
    public HttpResponse<?> create(HttpRequest<?> request, @Body Map<String, Object> body) {
        String name = body == null || body.get("name") == null
                ? "" : String.valueOf(body.get("name")).trim();
        String password = body == null || body.get("password") == null
                ? "" : String.valueOf(body.get("password"));
        if (!ACCOUNT_NAME_PATTERN.matcher(name).matches()) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_name"));
        }
        if (!validPassword(password)) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_password"));
        }
        if (accountRepository.findByName(name).isPresent()) {
            return HttpResponse.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "account_name_exists"));
        }

        String nick = inputString(body, "nick");
        String email = inputString(body, "email");
        String birthday = inputString(body, "birthday");
        String pin = inputString(body, "pin");
        String pic = inputString(body, "pic");
        Integer characterSlots = integerField(body, "characterSlots");
        Integer gender = integerField(body, "gender");
        Integer language = integerField(body, "language");
        Integer nxCredit = integerField(body, "nxCredit");
        Integer maplePoint = integerField(body, "maplePoint");
        Integer nxPrepaid = integerField(body, "nxPrepaid");
        Integer rewardPoints = integerField(body, "rewardPoints");
        Integer votePoints = integerField(body, "votePoints");
        Boolean tosValue = booleanField(body, "tosAccepted");
        characterSlots = characterSlots == null ? 3 : characterSlots;
        gender = gender == null ? 0 : gender;
        language = language == null ? 3 : language;
        nxCredit = nxCredit == null ? 0 : nxCredit;
        maplePoint = maplePoint == null ? 0 : maplePoint;
        nxPrepaid = nxPrepaid == null ? 0 : nxPrepaid;
        rewardPoints = rewardPoints == null ? 0 : rewardPoints;
        votePoints = votePoints == null ? 0 : votePoints;
        boolean tosAccepted = tosValue == null || tosValue;
        if (!validOptionalText(nick, 20)
                || !validOptionalText(email, 45)
                || (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches())
                || !validOptionalDate(birthday)
                || !validSecurityCode(pin, 4)
                || !validSecurityCode(pic, 6)
                || characterSlots < 1 || characterSlots > 15
                || !Set.of(0, 1, 10).contains(gender)
                || !Set.of(2, 3).contains(language)
                || !nonNegative(nxCredit, maplePoint, nxPrepaid, rewardPoints, votePoints)
                || (body != null && body.containsKey("tosAccepted") && tosValue == null)) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_profile"));
        }

        Account account = new Account();
        account.setName(name);
        account.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        account.setTemporaryPasswordHash("");
        account.setTemporaryPasswordExpiresAt("");
        account.setPin(pin);
        account.setPic(pic);
        account.setBirthday(birthday.isEmpty() ? "2005-05-11" : birthday);
        account.setCharacterSlots(characterSlots);
        account.setGender(gender);
        account.setLanguage(language);
        account.setTos(tosAccepted ? 1 : 0);
        account.setNick(nick);
        account.setEmail(email);
        account.setNxCredit(nxCredit);
        account.setMaplePoint(maplePoint);
        account.setNxPrepaid(nxPrepaid);
        account.setRewardPoints(rewardPoints);
        account.setVotePoints(votePoints);
        account.setWebAdmin(0);
        account.setMute(0);
        try {
            accountRepository.insert(account);
        } catch (RuntimeException exception) {
            // 预检查之后仍可能并发创建同名账号；把唯一键冲突稳定映射为 409。
            if (accountRepository.findByName(name).isPresent()) {
                return HttpResponse.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "account_name_exists"));
            }
            throw exception;
        }

        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE,
                "accountName=" + name + ",exists=false");
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "accountId=" + account.getId() + ",accountName=" + name + ",created=true");
        return HttpResponse.created(accountMap(account));
    }

    /** 修改账号资料；账号名和系统维护字段不可在此接口修改，密码留空表示保持不变。 */
    @Put("/{accountId}")
    public HttpResponse<?> update(HttpRequest<?> request,
                                  @PathVariable long accountId,
                                  @Body Map<String, Object> body) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        if (body == null) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_profile"));
        }

        String password = rawInputString(body, "password");
        String nick = inputString(body, "nick");
        String email = inputString(body, "email");
        String birthday = inputString(body, "birthday");
        String pin = inputString(body, "pin");
        String pic = inputString(body, "pic");
        Integer characterSlots = integerField(body, "characterSlots");
        Integer gender = integerField(body, "gender");
        Integer language = integerField(body, "language");
        Integer nxCredit = integerField(body, "nxCredit");
        Integer maplePoint = integerField(body, "maplePoint");
        Integer nxPrepaid = integerField(body, "nxPrepaid");
        Integer rewardPoints = integerField(body, "rewardPoints");
        Integer votePoints = integerField(body, "votePoints");
        Boolean tosAccepted = booleanField(body, "tosAccepted");

        if ((body.containsKey("password") && !password.isEmpty() && !validPassword(password))
                || (body.containsKey("nick") && !validOptionalText(nick, 20))
                || (body.containsKey("email") && (!validOptionalText(email, 45)
                    || (!email.isEmpty() && !EMAIL_PATTERN.matcher(email).matches())))
                || (body.containsKey("birthday") && !validOptionalDate(birthday))
                || (body.containsKey("pin") && !validSecurityCode(pin, 4))
                || (body.containsKey("pic") && !validSecurityCode(pic, 6))
                || (body.containsKey("characterSlots")
                    && (characterSlots == null || characterSlots < 1 || characterSlots > 15))
                || (body.containsKey("gender") && (gender == null || !Set.of(0, 1, 10).contains(gender)))
                || (body.containsKey("language") && (language == null || !Set.of(2, 3).contains(language)))
                || (body.containsKey("tosAccepted") && tosAccepted == null)
                || invalidOptionalNonNegative(body, "nxCredit", nxCredit)
                || invalidOptionalNonNegative(body, "maplePoint", maplePoint)
                || invalidOptionalNonNegative(body, "nxPrepaid", nxPrepaid)
                || invalidOptionalNonNegative(body, "rewardPoints", rewardPoints)
                || invalidOptionalNonNegative(body, "votePoints", votePoints)) {
            return HttpResponse.badRequest(Map.of("error", "invalid_account_profile"));
        }

        String before = accountProfileSummary(account);
        if (body.containsKey("password") && !password.isEmpty()) {
            account.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        }
        if (body.containsKey("nick")) account.setNick(nick);
        if (body.containsKey("email")) account.setEmail(email);
        if (body.containsKey("birthday") && !birthday.isEmpty()) account.setBirthday(birthday);
        if (body.containsKey("pin")) account.setPin(pin);
        if (body.containsKey("pic")) account.setPic(pic);
        if (body.containsKey("characterSlots")) account.setCharacterSlots(characterSlots);
        if (body.containsKey("gender")) account.setGender(gender);
        if (body.containsKey("language")) account.setLanguage(language);
        if (body.containsKey("tosAccepted")) account.setTos(tosAccepted ? 1 : 0);
        if (body.containsKey("nxCredit")) account.setNxCredit(nxCredit);
        if (body.containsKey("maplePoint")) account.setMaplePoint(maplePoint);
        if (body.containsKey("nxPrepaid")) account.setNxPrepaid(nxPrepaid);
        if (body.containsKey("rewardPoints")) account.setRewardPoints(rewardPoints);
        if (body.containsKey("votePoints")) account.setVotePoints(votePoints);
        accountRepository.update(account);

        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE, before);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE, accountProfileSummary(account));
        return HttpResponse.ok(accountMap(account));
    }

    /** 删除账号及其角色、角色存档和账号级关联数据；审计记录按不可抵赖原则保留。 */
    @Delete("/{accountId}")
    public HttpResponse<?> delete(HttpRequest<?> request, @PathVariable long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        List<Character> characters = characterRepository.findByAccount(accountId);
        Set<Long> characterIds = characters.stream().map(Character::getId).collect(Collectors.toSet());
        String before = "accountId=" + accountId + ",accountName=" + account.getName()
                + ",characters=" + characterIds.size();

        // 先禁止重新登录并断开内存态角色，避免删除期间的迟到存档重新写回关联表。
        account.setBanned(1);
        account.setLoggedIn(0);
        accountRepository.update(account);
        for (long characterId : characterIds) {
            adminService.kick(characterId);
        }
        if (!waitUntilOffline(characterIds)) {
            request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE, before);
            request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                    "accountId=" + accountId + ",deleteBlocked=charactersStillOnline");
            return HttpResponse.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "account_still_online"));
        }

        AccountDeletionRepository.DeletionResult result = accountDeletionRepository.deleteByAccountId(accountId);
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE, before);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",deleted=true,characters=" + result.characters()
                        + ",relatedRows=" + result.relatedRows());
        return HttpResponse.ok(Map.of(
                "deleted", true,
                "accountId", accountId,
                "characters", result.characters(),
                "relatedRows", result.relatedRows()));
    }

    /** 账号详情与所有世界的角色快照。 */
    @Get("/{accountId}")
    public HttpResponse<?> detail(@PathVariable long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        Set<Long> online = onlineCharacterIds();
        List<Map<String, Object>> characters = characterRepository.findByAccount(accountId).stream()
                .map(character -> characterMap(character, online.contains(character.getId())))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", accountMap(account));
        result.put("characters", characters);
        return HttpResponse.ok(result);
    }

    /** 更新封禁与禁言状态；封禁账号时同步踢下该账号所有在线角色。 */
    @Put("/{accountId}/restrictions")
    public HttpResponse<?> updateRestrictions(HttpRequest<?> request,
                                              @PathVariable long accountId,
                                              @Body Map<String, Object> body) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        Boolean banned = booleanField(body, "banned");
        Boolean muted = booleanField(body, "muted");
        if (banned == null && muted == null) {
            return HttpResponse.badRequest(Map.of("error", "restriction_fields_required"));
        }

        String before = restrictionSummary(account);
        if (banned != null) {
            account.setBanned(banned ? 1 : 0);
            account.setBanReason(banned ? safeString(body.get("banReason"), 256) : "");
        }
        if (muted != null) {
            account.setMute(muted ? 1 : 0);
        }

        int disconnected = 0;
        if (account.getBanned() == 1) {
            disconnected = disconnectAccount(accountId);
            account.setLoggedIn(0);
        }
        accountRepository.update(account);
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE, before);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE, restrictionSummary(account));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", true);
        result.put("disconnected", disconnected);
        result.put("account", accountMap(account));
        return HttpResponse.ok(result);
    }

    /** 踢下账号全部在线角色，并修复可能残留的 logged_in 状态。 */
    @Post("/{accountId}/force-offline")
    public HttpResponse<?> forceOffline(HttpRequest<?> request, @PathVariable long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        int beforeLoggedIn = account.getLoggedIn();
        int disconnected = disconnectAccount(accountId);
        account.setLoggedIn(0);
        accountRepository.update(account);
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",loggedIn=" + beforeLoggedIn);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",loggedIn=0,disconnected=" + disconnected);
        return HttpResponse.ok(Map.of(
                "forcedOffline", true,
                "accountId", accountId,
                "disconnected", disconnected));
    }

    /**
     * 生成一次性临时登录密码。重新生成会立即替换旧临时密码，玩家原密码不会改变。
     * 明文只在本响应中出现一次，响应显式禁止缓存。
     */
    @Post("/{accountId}/temporary-password")
    public HttpResponse<?> generateTemporaryPassword(HttpRequest<?> request,
                                                     @PathVariable long accountId,
                                                     @Body Map<String, Object> body) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        Integer durationMinutes = integerField(body, "durationMinutes");
        if (durationMinutes == null) {
            durationMinutes = DEFAULT_TEMPORARY_PASSWORD_MINUTES;
        }
        if (durationMinutes < MIN_TEMPORARY_PASSWORD_MINUTES
                || durationMinutes > MAX_TEMPORARY_PASSWORD_MINUTES) {
            return HttpResponse.badRequest(Map.of("error", "invalid_temporary_password_duration"));
        }

        String temporaryPassword = generateTemporaryPassword();
        String expiresAt = Instant.now().plus(Duration.ofMinutes(durationMinutes)).toString();
        boolean replaced = temporaryPasswordActive(account);
        account.setTemporaryPasswordHash(BCrypt.hashpw(temporaryPassword, BCrypt.gensalt()));
        account.setTemporaryPasswordExpiresAt(expiresAt);
        accountRepository.update(account);

        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",temporaryPasswordActive=" + replaced);
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "accountId=" + accountId + ",temporaryPasswordActive=true,expiresAt=" + expiresAt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated", true);
        result.put("accountId", accountId);
        result.put("temporaryPassword", temporaryPassword);
        result.put("expiresAt", expiresAt);
        result.put("oneTime", true);
        return HttpResponse.ok(result)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache");
    }

    private int disconnectAccount(long accountId) {
        int disconnected = 0;
        for (Character character : characterRepository.findByAccount(accountId)) {
            if (adminService.kick(character.getId())) {
                disconnected++;
            }
        }
        return disconnected;
    }

    private Set<Long> onlineCharacterIds() {
        AdminService.ChannelSummary summary = adminService.onlineSummary();
        if (summary == null || summary.players() == null) {
            return Set.of();
        }
        return summary.players().stream().map(AdminService.OnlinePlayer::characterId).collect(Collectors.toSet());
    }

    private Map<String, Object> accountMap(Account account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", account.getId());
        result.put("name", account.getName());
        result.put("banned", account.getBanned() == 1);
        result.put("banReason", safeString(account.getBanReason(), 256));
        result.put("muted", account.getMute() != null && account.getMute() == 1);
        result.put("loggedIn", account.getLoggedIn() == 1);
        result.put("lastLogin", safeString(account.getLastLogin(), 64));
        result.put("createdAt", safeString(account.getCreatedAt(), 64));
        result.put("tempBan", safeString(account.getTempBan(), 64));
        result.put("characterSlots", account.getCharacterSlots());
        result.put("gender", account.getGender());
        result.put("nick", safeString(account.getNick(), 20));
        result.put("email", safeString(account.getEmail(), 45));
        result.put("birthday", safeString(account.getBirthday(), 10));
        result.put("language", account.getLanguage());
        result.put("tosAccepted", account.getTos() == 1);
        result.put("nxCredit", account.getNxCredit() == null ? 0 : account.getNxCredit());
        result.put("maplePoint", account.getMaplePoint() == null ? 0 : account.getMaplePoint());
        result.put("nxPrepaid", account.getNxPrepaid() == null ? 0 : account.getNxPrepaid());
        result.put("rewardPoints", account.getRewardPoints());
        result.put("votePoints", account.getVotePoints());
        result.put("pinConfigured", account.getPin() != null && !account.getPin().isBlank());
        result.put("picConfigured", account.getPic() != null && !account.getPic().isBlank());
        result.put("temporaryPasswordActive", temporaryPasswordActive(account));
        result.put("temporaryPasswordExpiresAt",
                temporaryPasswordActive(account) ? safeString(account.getTemporaryPasswordExpiresAt(), 64) : "");
        return result;
    }

    private static Map<String, Object> characterMap(Character character, boolean online) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", character.getId());
        result.put("name", character.getName());
        result.put("world", character.getWorld());
        result.put("level", character.getLevel());
        result.put("job", character.getJob());
        result.put("map", character.getMap());
        result.put("meso", character.getMeso());
        result.put("fame", character.getFame());
        result.put("guildId", character.getGuildId());
        result.put("lastLogoutTime", safeString(character.getLastLogoutTime(), 64));
        result.put("online", online);
        return result;
    }

    private static Boolean booleanField(Map<String, Object> body, String field) {
        if (body == null) {
            return null;
        }
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        return null;
    }

    private static Integer integerField(Map<String, Object> body, String field) {
        if (body == null || !body.containsKey(field)) {
            return null;
        }
        Object value = body.get(field);
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static String inputString(Map<String, Object> body, String field) {
        return body == null || body.get(field) == null ? "" : String.valueOf(body.get(field)).trim();
    }

    private static String rawInputString(Map<String, Object> body, String field) {
        return body == null || body.get(field) == null ? "" : String.valueOf(body.get(field));
    }

    private static boolean validOptionalText(String value, int maxLength) {
        return value != null && value.length() <= maxLength;
    }

    private static boolean validPassword(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH
                && password.length() <= MAX_PASSWORD_LENGTH;
    }

    private static boolean validSecurityCode(String value, int length) {
        return value != null && (value.isEmpty() || value.matches("\\d{" + length + "}"));
    }

    private static boolean validOptionalDate(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return !date.isBefore(LocalDate.of(1900, 1, 1)) && !date.isAfter(LocalDate.now());
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static boolean nonNegative(Integer... values) {
        for (Integer value : values) {
            if (value == null || value < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean invalidOptionalNonNegative(Map<String, Object> body, String field, Integer value) {
        return body.containsKey(field) && (value == null || value < 0);
    }

    private boolean waitUntilOffline(Set<Long> characterIds) {
        if (characterIds.isEmpty()) {
            return true;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        do {
            if (onlineCharacterIds().stream().noneMatch(characterIds::contains)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static boolean temporaryPasswordActive(Account account) {
        String hash = account.getTemporaryPasswordHash();
        String expiresAt = account.getTemporaryPasswordExpiresAt();
        if (hash == null || hash.isBlank() || expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static String generateTemporaryPassword() {
        StringBuilder result = new StringBuilder(TEMPORARY_PASSWORD_LENGTH);
        for (int i = 0; i < TEMPORARY_PASSWORD_LENGTH; i++) {
            result.append(TEMPORARY_PASSWORD_ALPHABET[
                    SECURE_RANDOM.nextInt(TEMPORARY_PASSWORD_ALPHABET.length)]);
        }
        return result.toString();
    }

    private static String restrictionSummary(Account account) {
        return "accountId=" + account.getId()
                + ",banned=" + (account.getBanned() == 1)
                + ",muted=" + (account.getMute() != null && account.getMute() == 1);
    }

    private static String accountProfileSummary(Account account) {
        return "accountId=" + account.getId()
                + ",nick=" + safeString(account.getNick(), 20)
                + ",emailConfigured=" + (account.getEmail() != null && !account.getEmail().isBlank())
                + ",characterSlots=" + account.getCharacterSlots()
                + ",gender=" + account.getGender()
                + ",language=" + account.getLanguage()
                + ",tosAccepted=" + (account.getTos() == 1)
                + ",passwordChanged=redacted";
    }

    private static String safeString(Object value, int max) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
