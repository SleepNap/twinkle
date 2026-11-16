package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.mapper.PlayerCharacterRecordMapper;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的角色仓库（M1 选角列表 / M2 进图加载）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不再用 @Singleton 自注册。
 */
public class FlexPlayerCharacterRepository implements PlayerCharacterRepository {

    private final PlayerCharacterRecordMapper mapper;

    public FlexPlayerCharacterRepository(PlayerCharacterRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PlayerCharacterRecord> findByAccount(int accountId, int world) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(PlayerCharacterRecord::getAccountId).eq(accountId)
                .and(PlayerCharacterRecord::getWorld).eq(world)
                .orderBy(PlayerCharacterRecord::getLevel).desc());
    }

    @Override
    public Optional<PlayerCharacterRecord> findById(long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }

    @Override
    public List<PlayerCharacterRecord> findByAccount(long accountId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(PlayerCharacterRecord::getAccountId).eq(accountId)
                .orderBy(PlayerCharacterRecord::getWorld).asc()
                .orderBy(PlayerCharacterRecord::getLevel).desc());
    }

    @Override
    public Optional<PlayerCharacterRecord> findByName(String name) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(PlayerCharacterRecord::getName).eq(name)));
    }

    @Override
    public List<PlayerCharacterRecord> findByGuild(int guildId) {
        // 公会成员 = character_records.guild_id 命中该公会的全部角色（不另建成员表）。
        // 排序两参写法：单参 orderBy(Xxx::getY) 返回 QueryOrderByBuilder，写完不赋值等于没有 ORDER BY。
        // 先按职级升序（0=会长在最前），再按名字升序，保证成员列表展示稳定。
        QueryWrapper query = QueryWrapper.create();
        query.where(PlayerCharacterRecord::getGuildId).eq(guildId);
        query.orderBy(PlayerCharacterRecord::getGuildRank, true);
        query.orderBy(PlayerCharacterRecord::getName, true);
        return mapper.selectListByQuery(query);
    }

    @Override
    public boolean existsByName(String name) {
        return mapper.selectCountByQuery(
                QueryWrapper.create().where(PlayerCharacterRecord::getName).eq(name)) > 0;
    }

    @Override
    public void insert(PlayerCharacterRecord chr) {
        mapper.insertSelective(chr);
    }

    @Override
    public void save(PlayerCharacterRecord chr) {
        mapper.update(chr);
    }
}
