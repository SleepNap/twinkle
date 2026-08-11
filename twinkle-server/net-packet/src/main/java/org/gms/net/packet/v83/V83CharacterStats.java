package org.gms.net.packet.v83;

import java.util.Objects;

/**
 * v83 角色基础状态的协议投影。
 *
 * <p>只包含发包需要的值，不依赖 data/domain-game。登录进程与频道进程分别从自己的
 * 状态模型构造投影，协议层只负责稳定的小端字节布局。
 */
public record V83CharacterStats(
        int id,
        String name,
        int gender,
        int skinColor,
        int face,
        int hair,
        int level,
        int job,
        int strength,
        int dexterity,
        int intelligence,
        int luck,
        int hp,
        int maxHp,
        int mp,
        int maxMp,
        int ap,
        String sp,
        long exp,
        int fame,
        long gachaExp,
        int mapId,
        int spawnPoint) {

    public V83CharacterStats {
        Objects.requireNonNull(name, "name");
    }
}
