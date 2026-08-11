package org.gms.data.repo;

import org.gms.data.entity.SkillEntity;

import java.util.List;

/** 技能存档仓库。 */
public interface SkillRepository {

    List<SkillEntity> findByCharacterId(long characterId);

    void replaceAll(long characterId, List<SkillEntity> skills);
}
