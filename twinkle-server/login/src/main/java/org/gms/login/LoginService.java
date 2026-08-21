package org.gms.login;

import jakarta.inject.Singleton;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.data.repo.InventoryItemRepository;
import org.mindrot.jbcrypt.BCrypt;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 登录业务（架构 login：账号校验、选角，读 DB 经 repository 接口）。
 *
 * <p>M1 单世界（world=0）。密码用 BCrypt 散列校验（不落明文）。
 *
 * <p>封禁语义（红线 8）：{@code banned} 只有值 1 明确表示已封禁，其余（含 NULL）视为未封禁。
 */
@Singleton
public final class LoginService {

    /** 登录结果：account 非空 = 成功（errorCode=0）；否则 errorCode 为 v83 错误码。 */
    public record LoginResult(Account account, int errorCode) {
        public static LoginResult ok(Account account) {
            return new LoginResult(account, 0);
        }

        public static LoginResult error(int code) {
            return new LoginResult(null, code);
        }
    }

    private final AccountRepository accountRepository;
    private final CharacterRepository characterRepository;
    private final InventoryItemRepository inventoryItemRepository;

    /**
     * 全参构造（DI 用）。2 参构造仅供测试桩（inventoryItemRepository 为 null 时建角不写装备）。
     */
    @jakarta.inject.Inject
    public LoginService(AccountRepository accountRepository, CharacterRepository characterRepository,
                        InventoryItemRepository inventoryItemRepository) {
        this.accountRepository = accountRepository;
        this.characterRepository = characterRepository;
        this.inventoryItemRepository = inventoryItemRepository;
    }

    public LoginService(AccountRepository accountRepository, CharacterRepository characterRepository) {
        this(accountRepository, characterRepository, null);
    }

    /**
     * 账号 + 密码校验。
     *
     * @return 成功 errorCode=0 且 account 非空；失败返回 v83 错误码（3=封禁 / 4=密码错 / 5=账号不存在）
     */
    public LoginResult authenticate(String name, String password) {
        Optional<Account> acc = accountRepository.findByName(name);
        if (acc.isEmpty()) {
            return LoginResult.error(5); // 账号不存在
        }
        Account account = acc.get();
        if (account.getBanned() == 1) {
            return LoginResult.error(3); // 已封禁
        }
        if (matches(password, account.getPassword())) {
            return LoginResult.ok(account);
        }
        if (!temporaryPasswordActive(account) || !matches(password, account.getTemporaryPasswordHash())) {
            return LoginResult.error(4); // 密码错误
        }

        // 临时密码只允许成功登录一次。先清除再返回，玩家原密码始终不受影响。
        account.setTemporaryPasswordHash("");
        account.setTemporaryPasswordExpiresAt("");
        accountRepository.update(account);
        return LoginResult.ok(account);
    }

    private static boolean temporaryPasswordActive(Account account) {
        String expiresAt = account.getTemporaryPasswordExpiresAt();
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            return Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static boolean matches(String rawPassword, String hash) {
        if (rawPassword == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hash);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 某账号在世界下的角色列表（选角）。
     */
    public List<Character> charactersFor(long accountId, int world) {
        return characterRepository.findByAccount((int) accountId, world);
    }

    /**
     * 角色名是否可用（建角前置查重，v83 CHECK_CHAR_NAME）。
     *
     * <p>校验规则（思路参考 BeiDou-Server Character.canCreateChar）：
     * 2~12 位字母/数字/中文，且数据库中不存在同名角色。
     *
     * @return true = 名字可用（可建）
     */
    public boolean isNameAvailable(String name) {
        if (name == null) {
            return false;
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            return false;
        }
        return !characterRepository.existsByName(name);
    }

    /**
     * 新建角色（建角，v83 CREATE_CHAR）。
     *
     * @return 新建的角色；名字不可用 / 参数非法返回 null（调用方回建角失败包）
     */
    public Character createCharacter(long accountId, int world, String name, int job, int face,
                                     int hair, int skinColor, int top, int bottom, int shoes,
                                     int weapon, int gender) {
        if (!isNameAvailable(name)) {
            return null;
        }
        Character chr = new Character();
        chr.setAccountId(accountId);
        chr.setWorld(world);
        chr.setName(name);
        chr.setJob(job);
        chr.setLevel(1);
        chr.setHp((short) 50);
        chr.setMaxHp((short) 50);
        chr.setMp((short) 5);
        chr.setMaxMp((short) 5);
        chr.setStrStat((short) 12);
        chr.setDexStat((short) 5);
        chr.setLukStat((short) 4);
        chr.setIntStat((short) 4);
        chr.setFace(face);
        chr.setHair(hair);
        chr.setSkinColor(skinColor);
        chr.setGender(gender);
        chr.setAp(0);
        chr.setMap(10000);          // 蘑菇村（新手出生地，思路参考 BeiDou MapId.MUSHROOM_TOWN）
        chr.setSpawnPoint(0);
        chr.setBuddyCapacity(20);
        characterRepository.insert(chr);
        // 建角默认装备：v83 新手套（位置负值 = 已穿戴，思路参考 BeiDou CharacterFactory）
        // top=-5 / bottom=-6 / shoes=-7 / weapon=-11。inventory_type=1（EQUIP）。
        if (inventoryItemRepository != null) {
            insertDefaultEquip(chr, top, -5);
            insertDefaultEquip(chr, bottom, -6);
            insertDefaultEquip(chr, shoes, -7);
            insertDefaultEquip(chr, weapon, -11);
        }
        return chr;
    }

    private void insertDefaultEquip(Character chr, int itemId, int position) {
        if (itemId <= 0) {
            return;
        }
        InventoryItemEntity item = new InventoryItemEntity();
        item.setType(1);            // equip 行
        item.setCharacterId(chr.getId().intValue());
        item.setAccountId(chr.getAccountId().intValue());
        item.setItemId(itemId);
        item.setInventoryType(1);   // EQUIP
        item.setPosition(position); // 负值 = 已穿戴
        item.setQuantity(1);
        item.setOwner("");
        item.setPetId(0);
        item.setFlag(0);
        item.setExpiration(-1);
        item.setGiftFrom("");
        inventoryItemRepository.insert(item);
    }

    /**
     * 某角色已穿戴装备（inventory_items 中 position &lt; 0 的行，建角/选角外观编码用）。
     */
    public List<InventoryItemEntity> equippedItems(long characterId) {
        if (inventoryItemRepository == null) {
            return List.of();
        }
        return inventoryItemRepository.findByCharacterId(characterId).stream()
                .filter(e -> e.getPosition() < 0)
                .toList();
    }

    /** 建角名字校验：2~12 位字母/数字/中文（思路参考 BeiDou）。 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9\\u4e00-\\u9fa5]{2,12}");
}
