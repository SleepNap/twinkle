package org.gms.persistence.repo;

import org.gms.persistence.entity.GuildRecordEntity;

import java.util.Optional;

/**
 * 公会头仓储（V20 {@code guild_record}）。
 *
 * <p>成员关系查 {@link PlayerCharacterRepository#findByGuild(int)}，本仓储只管公会级数据。
 */
public interface GuildRecordRepository {

    /** 按世界 + 公会名查；名字在单世界内唯一。 */
    Optional<GuildRecordEntity> findByName(int world, String name);

    /** 按主键查。 */
    Optional<GuildRecordEntity> findById(long guildRecordId);

    /** 插入公会头并回填自增主键。 */
    GuildRecordEntity insert(GuildRecordEntity entity);

    /**
     * 整行更新公会头（名字、会长、容量、GP、职级称号、徽章、公告、联盟）。
     *
     * <p>乐观锁：{@code row_version} 必须等于 {@code expectedVersion}，命中时版本 +1 并刷新
     * {@code updated_at}；返回 {@code false} 表示版本冲突，调用方应丢弃缓存重读。
     */
    boolean update(GuildRecordEntity entity, int expectedVersion);

    /** 删除公会头。 */
    void delete(long guildRecordId);
}
