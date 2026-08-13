package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.transaction.Propagation;
import com.mybatisflex.core.transaction.TransactionalManager;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.entity.SkillEntity;
import org.gms.data.mapper.CharacterMapper;
import org.gms.data.mapper.InventoryItemMapper;
import org.gms.data.mapper.QuestProgressMapper;
import org.gms.data.mapper.QuestStatusMapper;
import org.gms.data.mapper.SkillMapper;
import org.gms.i18n.I18n;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MyBatis-Flex 角色、背包、任务与技能原子快照仓库。 */
public final class FlexCharacterSnapshotRepository implements CharacterSnapshotRepository {

    private final CharacterMapper characterMapper;
    private final InventoryItemMapper inventoryItemMapper;
    private final QuestStatusMapper questStatusMapper;
    private final QuestProgressMapper questProgressMapper;
    private final SkillMapper skillMapper;

    public FlexCharacterSnapshotRepository(CharacterMapper characterMapper,
                                           InventoryItemMapper inventoryItemMapper,
                                           QuestStatusMapper questStatusMapper,
                                           QuestProgressMapper questProgressMapper,
                                           SkillMapper skillMapper) {
        this.characterMapper = characterMapper;
        this.inventoryItemMapper = inventoryItemMapper;
        this.questStatusMapper = questStatusMapper;
        this.questProgressMapper = questProgressMapper;
        this.skillMapper = skillMapper;
    }

    @Override
    public void save(Character character, List<InventoryItemEntity> inventoryItems,
                     List<QuestStatusEntity> questStatuses,
                     List<QuestProgressSnapshot> questProgress,
                     List<SkillEntity> skills) {
        TransactionalManager.exec(() -> {
            characterMapper.update(character);
            inventoryItemMapper.deleteByQuery(QueryWrapper.create()
                    .where(InventoryItemEntity::getCharacterId).eq(character.getId()));
            for (InventoryItemEntity item : inventoryItems) {
                inventoryItemMapper.insert(item);
            }
            replaceQuests(character.getId(), questStatuses, questProgress);
            skillMapper.deleteByQuery(QueryWrapper.create()
                    .where(SkillEntity::getCharacterId).eq(character.getId()));
            for (SkillEntity skill : skills) {
                skillMapper.insert(skill);
            }
            return Boolean.TRUE;
        }, Propagation.REQUIRED, null);
    }

    private void replaceQuests(long characterId, List<QuestStatusEntity> statuses,
                               List<QuestProgressSnapshot> progress) {
        questProgressMapper.deleteByQuery(QueryWrapper.create()
                .where(QuestProgressEntity::getCharacterId).eq(characterId));
        questStatusMapper.deleteByQuery(QueryWrapper.create()
                .where(QuestStatusEntity::getCharacterId).eq(characterId));
        Map<Integer, Long> statusIds = new HashMap<>();
        for (QuestStatusEntity status : statuses) {
            questStatusMapper.insert(status);
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
            questProgressMapper.insert(entity);
        }
    }
}
