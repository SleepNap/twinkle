package org.gms.httpapi.application.admin;

import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.httpapi.mirror.OnlinePlayerMirror;
import org.gms.service.admin.AdminService;

import java.util.List;
import java.util.Optional;

/**
 * 管理侧 API 服务编排（架构 M3-1 数据三路：①查 DB ②经 service 接口 ③事件快照镜像）。
 *
 * <p>职责：把三个数据源编排成 Controller 可直接返回的 DTO，**不持有游戏内存对象**。
 * <ul>
 *   <li>① 角色存档 / 账号：直接经 data repository 查 DB（只读，DB 是持久化+查询层）。</li>
 *   <li>② 事务性操作（踢下线）：经 {@link AdminService}（单进程直调，分进程 RPC，接口不变）。</li>
 *   <li>③ 在线人数 / 在线列表：读 {@link OnlinePlayerMirror}（单向只读镜像）。</li>
 * </ul>
 *
 * <p>本类不加 @Singleton——由 bootstrap 装配（http-api 依赖 core+data，禁止依赖 channel/domain-game）。
 */
public final class AdminApiService {

    private final AccountRepository accountRepository;
    private final CharacterRepository characterRepository;
    private final AdminService adminService;
    private final OnlinePlayerMirror mirror;

    public AdminApiService(AccountRepository accountRepository, CharacterRepository characterRepository,
                           AdminService adminService, OnlinePlayerMirror mirror) {
        this.accountRepository = accountRepository;
        this.characterRepository = characterRepository;
        this.adminService = adminService;
        this.mirror = mirror;
    }

    // ---- ① 查 DB ----

    public Optional<AccountView> findAccount(String name) {
        return accountRepository.findByName(name).map(AdminApiService::accountView);
    }

    /** 某账号在世界下的角色列表（查 DB）。 */
    public List<CharacterView> charactersFor(long accountId, int world) {
        return characterRepository.findByAccount((int) accountId, world).stream()
                .map(AdminApiService::characterView)
                .toList();
    }

    /** 按角色 id 查存档（查 DB）。 */
    public Optional<Character> characterById(long characterId) {
        return characterRepository.findById(characterId);
    }

    // ---- ③ 只读镜像 ----

    public int onlineCount() {
        return mirror.onlineCount();
    }

    public List<AdminService.OnlinePlayer> onlinePlayers() {
        return mirror.snapshot().stream()
                .map(p -> new AdminService.OnlinePlayer(p.characterId(), p.name(), p.mapId(), p.level(), p.job()))
                .toList();
    }

    // ---- ② 经 service 接口 ----

    public boolean kick(long characterId) {
        return adminService.kick(characterId);
    }

    private static AccountView accountView(Account account) {
        return new AccountView(account.getId(), account.getName(), account.getBanned() == 1,
                account.getGender(), account.getCharacterSlots());
    }

    private static CharacterView characterView(Character character) {
        return new CharacterView(character.getId(), character.getName(), character.getLevel(),
                character.getJob(), character.getMap(), character.getMeso());
    }

    /** 与传输版本无关的账号查询投影，禁止向 Controller 泄露持久化实体。 */
    public record AccountView(Long id, String name, boolean banned, Integer gender,
                              Integer characterSlots) {
    }

    /** 与传输版本无关的角色查询投影，v1/v2 可分别映射自己的响应契约。 */
    public record CharacterView(Long id, String name, Integer level, Integer job, Integer map,
                                Integer meso) {
    }
}
