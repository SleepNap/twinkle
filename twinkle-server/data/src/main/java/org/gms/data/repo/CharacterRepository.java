package org.gms.data.repo;

import org.gms.data.entity.Character;

import java.util.List;
import java.util.Optional;

/**
 * 角色仓库（架构 M1 选角列表；M2 进图按 id 加载）。
 */
public interface CharacterRepository {

    /**
     * 某账号在指定世界下的角色列表（选角列表展示用）。
     *
     * @param accountId 账号 ID
     * @param world     世界 ID（v83 单世界多为 0）
     */
    List<Character> findByAccount(int accountId, int world);

    /**
     * 按角色 id 加载完整存档（进图用）。不存在返回 empty。
     */
    Optional<Character> findById(long id);
}
