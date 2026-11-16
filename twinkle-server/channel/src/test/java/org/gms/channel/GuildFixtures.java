package org.gms.channel;

import org.gms.concurrent.SerialTaskQueue;
import org.gms.persistence.entity.GuildRecordEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.repo.GuildRecordRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 公会回归共用的内存持久化桩与同步 IO 队列。
 *
 * <p>刻意选择，改测试前先看：
 *
 * <ul>
 *   <li><b>IO 队列用直接执行器</b>（{@link #inlineIo()}）：{@code GuildService.offload} 只在
 *       {@code io == null} <em>或</em> {@code sessions.execution() == null} 时内联执行；本夹具的
 *       {@link PlayerSessionRegistry} 不挂执行上下文，因此两条路径都同步。仍给一个非 null 队列，
 *       是为了让构造方式与生产装配一致，同时任务在调用线程跑完、不建线程、不 sleep。</li>
 *   <li><b>公会头只保留一份实例</b>：{@code notice} 先改缓存里的 head 再 {@code records.update}
 *       并自行把 {@code rowVersion} 置为 {@code expected + 1}（生产由 SQL 的
 *       {@code row_version = row_version + 1} 完成）。若 fake 也额外 +1，版本会被加两次，
 *       所以 {@link Guilds#update} 只校验并落在 {@code expectedVersion + 1} 上。</li>
 *   <li><b>{@link LinkedHashMap} 而非 {@code HashMap}</b>：整表包按
 *       {@code characters.findByGuild} 的返回顺序写成员段，断言「成员 id 段 → 成员属性段」时必须
 *       稳定顺序，否则排序靠哈希，用例会随 JDK/数据量漂移。</li>
 * </ul>
 *
 * <p>成员关系以角色行为唯一权威（{@code character_records.guild_id/guild_rank}），
 * 所以 {@code guildId}/{@code guildRank} 的断言一律落在 {@link Characters} 上，公会头只存公会级数据。
 */
public final class GuildFixtures {

    /** 测试世界 id：公会名在单世界内唯一（{@code guild_record} 的 (world, name) 语义）。 */
    public static final int WORLD = 1;

    /** v83 默认成员上限，与 {@link GuildRecordEntity#getCapacity()} 默认值一致。 */
    public static final int CAPACITY = 10;

    /** 成员名在整表包里的定长宽度（GBK 字节），与 {@code GuildPackets} 内部常量对齐。 */
    public static final int NAME_LENGTH = 13;

    private GuildFixtures() {
    }

    /**
     * 同步 IO 队列：{@code submit} 的任务在调用线程内跑完。
     *
     * <p>只为让 {@code offload} 拿到非 null 队列而不引入线程；容量给足以免任务被拒。
     */
    public static SerialTaskQueue inlineIo() {
        return new SerialTaskQueue(Runnable::run, 1024);
    }

    /** 造一条角色存档行（成员关系权威）；{@code level}/{@code job} 供整表包字段断言。 */
    public static PlayerCharacterRecord character(long id, String name, int level, int job) {
        PlayerCharacterRecord record = new PlayerCharacterRecord();
        record.setId(id);
        record.setName(name);
        record.setLevel(level);
        record.setJob(job);
        record.setGuildId(0);
        record.setGuildRank(5);
        return record;
    }

    /** 造一条公会头：职级称号与徽章走实体默认值，与生产建会的落库形态一致。 */
    public static GuildRecordEntity head(String name, long leaderId) {
        GuildRecordEntity entity = new GuildRecordEntity();
        entity.setWorld(WORLD);
        entity.setName(name);
        entity.setLeaderId(leaderId);
        entity.setCapacity(CAPACITY);
        entity.setRowVersion(0);
        return entity;
    }

    /** 内存公会头表：{@code insert} 回填自增 id 与 {@code row_version=0}，{@code update} 走版本门。 */
    public static final class Guilds implements GuildRecordRepository {
        public final Map<Long, GuildRecordEntity> rows = new LinkedHashMap<>();
        private long nextId = 1L;

        /** 预置一行公会头（用例需要「库里已有同名/已存在的公会」时用）。 */
        public GuildRecordEntity seed(GuildRecordEntity row) {
            return insert(row);
        }

        /** 断言用取行：不存在说明前置步骤没建行，直接暴露成失败而不是后续 NPE。 */
        public GuildRecordEntity byId(long guildId) {
            GuildRecordEntity row = rows.get(guildId);
            if (row == null) {
                throw new AssertionError("公会头不存在，guildId=" + guildId);
            }
            return row;
        }

        /** 按名字取行（单世界内唯一）。 */
        public GuildRecordEntity byName(String name) {
            for (GuildRecordEntity row : rows.values()) {
                if (name.equals(row.getName())) {
                    return row;
                }
            }
            throw new AssertionError("公会头不存在，name=" + name);
        }

        @Override public Optional<GuildRecordEntity> findByName(int world, String name) {
            for (GuildRecordEntity row : rows.values()) {
                if (row.getWorld() == world && name != null && name.equals(row.getName())) {
                    return Optional.of(row);
                }
            }
            return Optional.empty();
        }

        @Override public Optional<GuildRecordEntity> findById(long guildRecordId) {
            return Optional.ofNullable(rows.get(guildRecordId));
        }

        @Override public GuildRecordEntity insert(GuildRecordEntity entity) {
            entity.setGuildRecordId(nextId++);
            entity.setRowVersion(0);
            rows.put(entity.getGuildRecordId(), entity);
            return entity;
        }

        @Override public boolean update(GuildRecordEntity entity, int expectedVersion) {
            GuildRecordEntity current = rows.get(entity.getGuildRecordId());
            if (current == null) {
                return false;
            }
            if (current.getRowVersion() != expectedVersion) {
                return false;
            }
            rows.put(entity.getGuildRecordId(), entity);
            entity.setRowVersion(expectedVersion + 1);
            return true;
        }

        @Override public void delete(long guildRecordId) {
            rows.remove(guildRecordId);
        }
    }

    /** 内存角色表：成员关系（guildId/guildRank）的唯一权威，{@code save} 整行覆盖。 */
    public static final class Characters implements PlayerCharacterRepository {
        public final Map<Long, PlayerCharacterRecord> rows = new LinkedHashMap<>();

        /** 预置一条角色行，返回可继续断言的同一实例。 */
        public PlayerCharacterRecord seed(long id, String name, int level, int job) {
            PlayerCharacterRecord record = character(id, name, level, job);
            rows.put(id, record);
            return record;
        }

        /** 断言用取行：不存在说明前置步骤没建行，直接暴露成失败。 */
        public PlayerCharacterRecord byId(long id) {
            PlayerCharacterRecord record = rows.get(id);
            if (record == null) {
                throw new AssertionError("角色行不存在，id=" + id);
            }
            return record;
        }

        @Override public List<PlayerCharacterRecord> findByAccount(int accountId, int world) {
            List<PlayerCharacterRecord> result = new ArrayList<>();
            for (PlayerCharacterRecord record : rows.values()) {
                if (Objects.equals(record.getAccountId(), (long) accountId) && record.getWorld() == world) {
                    result.add(record);
                }
            }
            return result;
        }

        @Override public Optional<PlayerCharacterRecord> findById(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override public Optional<PlayerCharacterRecord> findByName(String name) {
            for (PlayerCharacterRecord record : rows.values()) {
                if (record.getName() != null && record.getName().equals(name)) {
                    return Optional.of(record);
                }
            }
            return Optional.empty();
        }

        /** 按 {@code guild_id} 过滤；guildId ≤ 0（无公会）一律空集，避免把全库角色当成员。 */
        @Override public List<PlayerCharacterRecord> findByGuild(int guildId) {
            List<PlayerCharacterRecord> result = new ArrayList<>();
            if (guildId <= 0) {
                return result;
            }
            for (PlayerCharacterRecord record : rows.values()) {
                if (record.getGuildId() == guildId) {
                    result.add(record);
                }
            }
            return result;
        }

        @Override public boolean existsByName(String name) {
            return findByName(name).isPresent();
        }

        @Override public void insert(PlayerCharacterRecord chr) {
            if (chr.getId() != null) {
                rows.put(chr.getId(), chr);
            }
        }

        /** 整行覆盖（生产是 updateById 全列 UPDATE）；{@code persistMembership} 靠它写回公会归属。 */
        @Override public void save(PlayerCharacterRecord chr) {
            if (chr.getId() != null) {
                rows.put(chr.getId(), chr);
            }
        }
    }
}
