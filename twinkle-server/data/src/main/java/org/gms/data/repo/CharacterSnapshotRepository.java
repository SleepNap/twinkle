package org.gms.data.repo;

import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.entity.SkillEntity;

import java.util.List;

/** 角色主表、完整背包（含宠物实例）、任务与技能的原子存档入口。 */
public interface CharacterSnapshotRepository {

    /**
     * 在同一数据库事务中覆盖角色全部持久化状态；任一步失败时必须整体回滚。
     */
    void save(Character character, List<InventoryItemEntity> inventoryItems,
              List<QuestStatusEntity> questStatuses,
              List<QuestProgressSnapshot> questProgress,
              List<SkillEntity> skills);
}
