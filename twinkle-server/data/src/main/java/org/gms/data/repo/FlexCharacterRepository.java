package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.Character;
import org.gms.data.mapper.CharacterMapper;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的角色仓库（M1 选角列表 / M2 进图加载）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不再用 @Singleton 自注册。
 */
public class FlexCharacterRepository implements CharacterRepository {

    private final CharacterMapper mapper;

    public FlexCharacterRepository(CharacterMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<Character> findByAccount(int accountId, int world) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(Character::getAccountid).eq(accountId)
                .and(Character::getWorld).eq(world)
                .orderBy(Character::getLevel).desc());
    }

    @Override
    public Optional<Character> findById(long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }
}
