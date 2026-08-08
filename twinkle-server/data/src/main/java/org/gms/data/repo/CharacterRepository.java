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

    /**
     * 落库角色存档（L4 增量 FLUSH，红线 17：只刷脏数据，不做全量落盘）。
     *
     * <p>MyBatis-Flex {@code updateById}，全 74 列参数化 UPDATE（红线 7：复杂原生 SQL 兜底参数化）。
     * 行不存在时更新 0 行（SQLite 不报错，业务方按需处理）。
     */
    void save(Character chr);
}
