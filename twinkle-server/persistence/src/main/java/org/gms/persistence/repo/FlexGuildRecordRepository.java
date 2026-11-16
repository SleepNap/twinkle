package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.gms.persistence.entity.GuildRecordEntity;
import org.gms.persistence.mapper.GuildRecordMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的公会头仓库（V20 {@code guild_record} 持久化）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不用 @Singleton 自注册。
 *
 * <p>查询一律分语句构造：{@code QueryWrapper.create().where(...).eq(...)} 之后继续
 * {@code .orderBy(...)} 返回的是 {@code QueryOrderByBuilder}，不能直接交给
 * {@code selectListByQuery}，所以条件与排序都写回同一个 {@link QueryWrapper} 变量。
 */
public class FlexGuildRecordRepository implements GuildRecordRepository {

    /** {@code updated_at} 统一写 UTC 文本，格式与 sqlite 的 {@code datetime('now')} 一致。 */
    private static final DateTimeFormatter UPDATED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final GuildRecordMapper mapper;

    public FlexGuildRecordRepository(GuildRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<GuildRecordEntity> findByName(int world, String name) {
        // 公会名在单世界内唯一：世界 + 名字两个条件一起查，避免跨世界同名公会串味。
        QueryWrapper query = QueryWrapper.create();
        query.where(GuildRecordEntity::getWorld).eq(world);
        query.and(GuildRecordEntity::getName).eq(name);
        List<GuildRecordEntity> rows = mapper.selectListByQuery(query);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public Optional<GuildRecordEntity> findById(long guildRecordId) {
        return Optional.ofNullable(mapper.selectOneById(guildRecordId));
    }

    @Override
    public GuildRecordEntity insert(GuildRecordEntity entity) {
        // 注意：{@code insert(entity)} 不会跳过 null 列，而 {@code guild_record.updated_at} 是 NOT NULL；
        // 实体默认 updatedAt 为 null，直接插会被约束拦下，所以在持久化层补盖当前 UTC 时间戳。
        if (entity.getUpdatedAt() == null || entity.getUpdatedAt().isBlank()) {
            entity.setUpdatedAt(currentUpdatedAt());
        }
        // 自增主键由 BaseMapper 回填到 entity.guildRecordId。
        mapper.insert(entity);
        return entity;
    }

    @Override
    public boolean update(GuildRecordEntity entity, int expectedVersion) {
        // 乐观锁：主键 + row_version 同时命中才写。updateByQuery(entity, qw) 会把实体里
        // 非 null 的原始类型字段一起写回（int 默认值会把无关列刷成 0），只能用 UpdateChain 逐列 set；
        // 主键列不 set，靠 where 定位。
        return UpdateChain.of(mapper)
                .where(GuildRecordEntity::getGuildRecordId).eq(entity.getGuildRecordId())
                .and(GuildRecordEntity::getRowVersion).eq(expectedVersion)
                .set(GuildRecordEntity::getWorld, entity.getWorld())
                .set(GuildRecordEntity::getName, entity.getName())
                .set(GuildRecordEntity::getLeaderId, entity.getLeaderId())
                .set(GuildRecordEntity::getCapacity, entity.getCapacity())
                .set(GuildRecordEntity::getPointGp, entity.getPointGp())
                .set(GuildRecordEntity::getRankTitle1, entity.getRankTitle1())
                .set(GuildRecordEntity::getRankTitle2, entity.getRankTitle2())
                .set(GuildRecordEntity::getRankTitle3, entity.getRankTitle3())
                .set(GuildRecordEntity::getRankTitle4, entity.getRankTitle4())
                .set(GuildRecordEntity::getRankTitle5, entity.getRankTitle5())
                .set(GuildRecordEntity::getLogoBackground, entity.getLogoBackground())
                .set(GuildRecordEntity::getLogoBackgroundColor, entity.getLogoBackgroundColor())
                .set(GuildRecordEntity::getLogoMark, entity.getLogoMark())
                .set(GuildRecordEntity::getLogoMarkColor, entity.getLogoMarkColor())
                .set(GuildRecordEntity::getNotice, entity.getNotice())
                .set(GuildRecordEntity::getSignature, entity.getSignature())
                .set(GuildRecordEntity::getAllianceId, entity.getAllianceId())
                .set(GuildRecordEntity::getRowVersion, expectedVersion + 1)
                .set(GuildRecordEntity::getUpdatedAt, currentUpdatedAt())
                .update();
    }

    @Override
    public void delete(long guildRecordId) {
        // 成员关系不在这里：character_records.guild_id 由调用方（公会解散链路）负责复位。
        mapper.deleteById(guildRecordId);
    }

    /** 当前 UTC 文本时间（{@code YYYY-MM-DD HH:mm:ss}）。 */
    private static String currentUpdatedAt() {
        return LocalDateTime.now(ZoneOffset.UTC).format(UPDATED_AT_FORMATTER);
    }
}
