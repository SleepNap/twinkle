package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.concurrent.GameExecution;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 频道玩家在线表（架构 M2 频道：内存态在线角色登记）。
 *
 * <p>在线表跨线程可枚举引用；注册/删除与角色修改归所属频道，跨线程业务读取必须取快照。
 * 只做增删查（纯数据结构）；"进图/下线"编排在频道 service。
 */
public final class PlayerStorage {

    private final ConcurrentMap<Long, PlayerCharacter> players = new ConcurrentHashMap<>();
    private final GameExecution execution;

    public PlayerStorage() { this(null); }
    public PlayerStorage(GameExecution execution) { this.execution = execution; }
    public GameExecution execution() { return execution; }

    /** 角色进图登记。 */
    public void add(PlayerCharacter chr) {
        if (execution != null) { execution.requireOwner(); chr.bindExecution(execution); }
        players.put(chr.getId(), chr);
    }

    /** 角色下线/换图移除（compare-and-remove：仅当登记对象==本对象才删，防旧代际误删新角色）。 */
    public void remove(PlayerCharacter chr) {
        if (execution != null) execution.requireOwner();
        players.remove(chr.getId(), chr);
    }

    public PlayerCharacter getById(long id) {
        return players.get(id);
    }

    public int count() {
        return players.size();
    }

    /** 全部在线角色（不可变视图）。 */
    public Collection<PlayerCharacter> all() {
        return List.copyOf(players.values());
    }
}
