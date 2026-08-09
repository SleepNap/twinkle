package org.gms.channel;

import org.gms.domain.game.Character;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 频道玩家在线表（架构 M2 频道：内存态在线角色登记）。
 *
 * <p>游戏 tick 单线程，但广播/断开可能并发到达，用 {@link ConcurrentMap} 兜底。
 * 只做增删查（纯数据结构）；"进图/下线"编排在频道 service。
 */
public final class PlayerStorage {

    private final ConcurrentMap<Long, Character> players = new ConcurrentHashMap<>();

    /** 角色进图登记。 */
    public void add(Character chr) {
        players.put(chr.getId(), chr);
    }

    /** 角色下线/换图移除（compare-and-remove：仅当登记对象==本对象才删，防旧代际误删新角色）。 */
    public void remove(Character chr) {
        players.remove(chr.getId(), chr);
    }

    public Character getById(long id) {
        return players.get(id);
    }

    public int count() {
        return players.size();
    }

    /** 全部在线角色（不可变视图）。 */
    public Collection<Character> all() {
        return List.copyOf(players.values());
    }
}
