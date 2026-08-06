package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;

import java.util.List;

/**
 * MyBatis-Flex 实现的任务存档仓库（M3-5 存档）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不用 @Singleton 自注册。
 */
public class FlexQuestRepository implements QuestRepository {

    private final QuestStatusMapper statusMapper;
    private final QuestProgressMapper progressMapper;

    public FlexQuestRepository(QuestStatusMapper statusMapper, QuestProgressMapper progressMapper) {
        this.statusMapper = statusMapper;
        this.progressMapper = progressMapper;
    }

    @Override
    public List<QuestStatusEntity> findStatusesByCharacterId(long characterId) {
        return statusMapper.selectListByQuery(QueryWrapper.create()
                .where(QuestStatusEntity::getCharacterid).eq(characterId));
    }

    @Override
    public List<QuestProgressEntity> findProgressByCharacterId(long characterId) {
        return progressMapper.selectListByQuery(QueryWrapper.create()
                .where(QuestProgressEntity::getCharacterid).eq(characterId));
    }

    @Override
    public void replaceAll(long characterId, List<QuestStatusEntity> statuses, List<QuestProgressEntity> progress) {
        statusMapper.deleteByQuery(QueryWrapper.create()
                .where(QuestStatusEntity::getCharacterid).eq(characterId));
        for (QuestStatusEntity status : statuses) {
            statusMapper.insert(status);
        }
        for (QuestProgressEntity p : progress) {
            progressMapper.insert(p);
        }
    }
}
