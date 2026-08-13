package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.transaction.Propagation;
import com.mybatisflex.core.transaction.TransactionalManager;
import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.i18n.I18n;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

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
                .where(QuestStatusEntity::getCharacterId).eq(characterId));
    }

    @Override
    public List<QuestProgressEntity> findProgressByCharacterId(long characterId) {
        return progressMapper.selectListByQuery(QueryWrapper.create()
                .where(QuestProgressEntity::getCharacterId).eq(characterId)
                .orderBy(QuestProgressEntity::getId).asc());
    }

    @Override
    public void replaceAll(long characterId, List<QuestStatusEntity> statuses,
                           List<QuestProgressSnapshot> progress) {
        TransactionalManager.exec(() -> {
            replace(characterId, statuses, progress);
            return Boolean.TRUE;
        }, Propagation.REQUIRED, null);
    }

    private void replace(long characterId, List<QuestStatusEntity> statuses,
                         List<QuestProgressSnapshot> progress) {
        progressMapper.deleteByQuery(QueryWrapper.create()
                .where(QuestProgressEntity::getCharacterId).eq(characterId));
        statusMapper.deleteByQuery(QueryWrapper.create()
                .where(QuestStatusEntity::getCharacterId).eq(characterId));
        Map<Integer, Long> statusIds = new HashMap<>();
        for (QuestStatusEntity status : statuses) {
            statusMapper.insert(status);
            statusIds.put(status.getQuest(), status.getQuestStatusId());
        }
        for (QuestProgressSnapshot snapshot : progress) {
            Long statusId = statusIds.get(snapshot.questId());
            if (statusId == null) {
                throw new IllegalArgumentException(I18n.message("error.quest.progress_missing_status", snapshot.questId()));
            }
            QuestProgressEntity entity = new QuestProgressEntity();
            entity.setCharacterId(Math.toIntExact(characterId));
            entity.setQuestStatusId(statusId.intValue());
            entity.setProgressId(snapshot.progressId());
            entity.setProgress(snapshot.progress());
            progressMapper.insert(entity);
        }
    }
}
