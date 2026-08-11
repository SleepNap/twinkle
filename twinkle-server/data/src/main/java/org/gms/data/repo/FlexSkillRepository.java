package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.transaction.Propagation;
import com.mybatisflex.core.transaction.TransactionalManager;
import org.gms.data.entity.SkillEntity;
import org.gms.data.mapper.SkillMapper;

import java.util.List;

/** MyBatis-Flex 技能存档仓库。 */
public final class FlexSkillRepository implements SkillRepository {

    private final SkillMapper mapper;

    public FlexSkillRepository(SkillMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<SkillEntity> findByCharacterId(long characterId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(SkillEntity::getCharacterId).eq(characterId)
                .orderBy(SkillEntity::getSkillId).asc());
    }

    @Override
    public void replaceAll(long characterId, List<SkillEntity> skills) {
        TransactionalManager.exec(() -> {
            mapper.deleteByQuery(QueryWrapper.create()
                    .where(SkillEntity::getCharacterId).eq(characterId));
            for (SkillEntity skill : skills) {
                mapper.insert(skill);
            }
            return Boolean.TRUE;
        }, Propagation.REQUIRED, null);
    }
}
