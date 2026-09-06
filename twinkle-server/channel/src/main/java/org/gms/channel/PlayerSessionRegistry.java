package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.PlayerCharacter;
import org.gms.concurrent.GameExecution;
import java.util.function.Supplier;
import org.gms.domain.game.spi.CharacterState;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 频道会话注册表（角色 id → 连接会话，M3 广播/交易的前置缺件）。
 *
 * <p>进图时认领、断链/下线时注销。广播经 {@code MapleMap.characters()} 的角色 id
 * 反查会话发包——不做跨操作状态，只做查表（红线 12）。
 *
 * <p><b>会话代际</b>（事故报告阶段 B / §5.4）：注册表持有 {@link Entry(sessionId, generation,
 * session)} 三元组。角色被某连接认领时生成单调递增的 {@code generation}（新代际）；断链注销
 * 用 compare-and-remove（仅当登记的会话引用==本会话才删），旧连接迟到关闭因代际不匹配
 * 只能成为空操作（记 {@link #supersededCleanupRejectedCount()}），不能清理新一代会话。
 *
 * <p>频道登记和业务清理由所属执行入口串行完成；ConcurrentMap 只支持跨线程定位会话。
 */
public final class PlayerSessionRegistry {
    private final GameExecution execution;
    public PlayerSessionRegistry() { this(null); }
    public PlayerSessionRegistry(GameExecution execution) { this.execution = execution; }
    public GameExecution execution() { return execution; }
    /** 频道跨角色业务的统一入口；独立测试仍使用同一边界保护。 */
    public <T> T coordinate(Supplier<T> action) {
        if (execution != null) return execution.call(action);
        synchronized (this) { return action.get(); }
    }

    /**
     * 会话登记（连接不可变 sessionId + 认领代际 + 会话引用，报告 §5.4 归属三元组）。
     *
     * <p>public：{@link #entry(long)} 为 public 返回它，公共方法不得返回包私有/私有类型
     * （红线 12），故须为 public。
     */
    public record Entry(long sessionId, long generation, PacketSession session) {
    }

    private final ConcurrentMap<Long, Entry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Object> loading = new ConcurrentHashMap<>();
    private final AtomicLong generationSeq = new AtomicLong();
    private final PlayerVisibilityService visibility = new PlayerVisibilityService(this);

    public PlayerVisibilityService visibility() { return visibility; }

    /** 异步读档期间先占住角色入口，避免两个连接同时读旧档再相互覆盖。 */
    public boolean beginLogin(long characterId, Object token) {
        if (execution != null) execution.requireOwner();
        return loading.putIfAbsent(characterId, token) == null;
    }

    public void endLogin(long characterId, Object token) {
        if (execution != null) execution.requireOwner();
        loading.remove(characterId, token);
    }
    /** 旧代际清理被拒次数（报告 §七：可观测，标准 3 证据）。 */
    private final AtomicLong supersededCleanupRejected = new AtomicLong();

    /**
     * 玩家进图认领会话：覆盖旧连接登记，生成新代际。
     *
     * @return 新代际（写入 session attr，供断链/租约层判归属）
     */
    public long claim(long characterId, PacketSession session) {
        if (execution != null) execution.requireOwner();
        long gen = generationSeq.incrementAndGet();
        Entry previous = sessions.put(characterId, new Entry(session.sessionId(), gen, session));
        if (previous != null && previous.session() != session) {
            Runnable cancelTrade = previous.session().getAttr("cancelTrade");
            if (cancelTrade != null) cancelTrade.run();
            visibility.leave(previous.session());
            PlayerCharacter old = previous.session().getAttr("character");
            if (old != null && old.getMapObject() != null) old.getMapObject().removeCharacter(old);
        }
        return gen;
    }

    /** 当前有效登记的会话 id（角色离线返回 -1）。 */
    public long sessionIdOf(long characterId) {
        Entry e = sessions.get(characterId);
        return e == null ? -1 : e.sessionId();
    }

    /** 当前有效登记的认领代际（角色离线返回 -1）。 */
    public long generationOf(long characterId) {
        Entry e = sessions.get(characterId);
        return e == null ? -1 : e.generation();
    }

    /**
     * 玩家下线/断链注销（compare-and-remove）。
     *
     * @return 是否真的删除了本会话登记；false = 本会话已被新代际替代（旧连接迟到关闭，
     *         只记计数不清理）。
     */
    public boolean unregister(long characterId, PacketSession session) {
        if (execution != null) execution.requireOwner();
        Entry[] removed = new Entry[1];
        sessions.compute(characterId, (k, e) -> {
            if (e != null && e.session() == session) {
                removed[0] = e;
                return null;
            }
            return e;
        });
        if (removed[0] == null) {
            supersededCleanupRejected.incrementAndGet();
            return false;
        }
        visibility.leave(session);
        return true;
    }

    /** 查当前有效会话（Whisper/Buddy/交易等按角色取会话发包）。 */
    public PacketSession get(long characterId) {
        Entry e = sessions.get(characterId);
        return e == null ? null : e.session();
    }

    /** 当前有效登记（sessionId + generation，代际比对用；返回类型 public，红线 12 合法）。 */
    public Optional<Entry> entry(long characterId) {
        return Optional.ofNullable(sessions.get(characterId));
    }

    public int count() {
        return sessions.size();
    }

    /** 全部在线会话（不可变视图）。 */
    public Collection<PacketSession> all() {
        return sessions.values().stream().map(Entry::session).toList();
    }

    /** 旧代际清理被拒次数（事故报告 §七：迟到断链不能误删新会话的观测证据）。 */
    public long supersededCleanupRejectedCount() {
        return supersededCleanupRejected.get();
    }

    /** 向一张地图内的全部玩家广播（可替换层/channel 用，地图内角色取会话发包）。 */
    public void broadcastToMap(MapleMap map, OutPacket packet) {
        broadcastToMap(map, packet, -1);
    }

    /** 向地图内除 excludeId 外的全部玩家广播（移动/攻击不回显给发包者自己）。 */
    public void broadcastToMap(MapleMap map, OutPacket packet, long excludeId) {
        for (CharacterState chr : map.characters()) {
            if (chr.getId() == excludeId) {
                continue;
            }
            Entry e = sessions.get(chr.getId());
            if (e != null && e.session().stage() == SessionStage.IN_GAME
                    && e.session().getAttr("character") == chr && e.session().getAttr("mapTransition") == null
                    && !Boolean.FALSE.equals(e.session().getAttr("mapVisibilityReady"))) {
                e.session().send(packet);
            }
        }
    }
}
