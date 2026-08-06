package org.gms.data.repo;

import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;

import java.util.List;

/**
 * 任务存档仓库（架构 M3-5 存档：进图回填 + 落库）。
 */
public interface QuestRepository {

    /** 某角色的全部任务状态（回填内存态用）。 */
    List<QuestStatusEntity> findStatusesByCharacterId(long characterId);

    /** 某角色的全部任务进度行。 */
    List<QuestProgressEntity> findProgressByCharacterId(long characterId);

    /** 覆盖落库：删除旧行 + 插入当前快照（含状态与进度）。 */
    void replaceAll(long characterId, List<QuestStatusEntity> statuses, List<QuestProgressEntity> progress);
}
