package org.gms.login;

import jakarta.inject.Singleton;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.mindrot.jbcrypt.BCrypt;

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

    public LoginService(AccountRepository accountRepository, CharacterRepository characterRepository) {
        this.accountRepository = accountRepository;
        this.characterRepository = characterRepository;
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
        String stored = account.getPassword();
        if (stored == null || stored.isEmpty() || !BCrypt.checkpw(password, stored)) {
            return LoginResult.error(4); // 密码错误
        }
        return LoginResult.ok(account);
    }

    /**
     * 某账号在世界下的角色列表（选角）。
     */
    public List<Character> charactersFor(long accountId, int world) {
        return characterRepository.findByAccount((int) accountId, world);
    }
}
