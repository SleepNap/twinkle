package org.gms.persistence.repo;

import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.entity.InventoryItemEntity;
import org.gms.persistence.entity.QuestStatusEntity;
import org.gms.persistence.entity.SkillEntity;

import java.util.List;

/** 角色主表、完整背包（含宠物实例）、任务与技能的原子存档入口。 */
public interface PlayerCharacterSnapshotRepository {

    /**
     * 在同一数据库事务中覆盖角色全部持久化状态；任一步失败时必须整体回滚。
     */
    void save(PlayerCharacterRecord character, List<InventoryItemEntity> inventoryItems,
              List<QuestStatusEntity> questStatuses,
              List<QuestProgressSnapshot> questProgress,
              List<SkillEntity> skills);
}
