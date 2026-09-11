package org.gms.login;

import org.gms.persistence.entity.GameAccountRecord;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.entity.InventoryItemEntity;
import java.util.List;

/** 登录业务稳定契约，实现由 login-logic 提供。 */
public interface LoginService {
    public record LoginResult(GameAccountRecord account, int errorCode) {
        public static LoginResult ok(GameAccountRecord account) {
            return new LoginResult(account, 0);
        }

        public static LoginResult error(int code) {
            return new LoginResult(null, code);
        }
    }

    public LoginResult authenticate(String name, String password);

    public List<PlayerCharacterRecord> charactersFor(long accountId, int world);

    public boolean isNameAvailable(String name);

    public PlayerCharacterRecord createCharacter(long accountId, int world, String name, int job, int face,
                                     int hair, int skinColor, int top, int bottom, int shoes,
                                     int weapon, int gender);

    public List<InventoryItemEntity> equippedItems(long characterId);
}
