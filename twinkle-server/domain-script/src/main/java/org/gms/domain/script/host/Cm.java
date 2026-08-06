package org.gms.domain.script.host;

/**
 * 宿主对象契约 cm（character manager，架构 M0 第 9 项）。
 *
 * <p>脚本通过 {@code cm} 访问角色信息（等级/职业/HP/MP/位置等）。
 * 实现类在稳定层（domain-game）或可替换层（replaceable）；脚本只依赖此接口，
 * 不直接触碰 {@code org.gms.domain.game.Character} 具体类（红线 11/12）。
 *
 * <p>方法签名按 v83 脚本兼容约定命名，参数与返回值用基本类型/字符串，避免宿主复杂对象泄漏。
 */
public interface Cm {

    /** 角色名。 */
    String getName();

    /** 等级。 */
    int getLevel();

    /** 职业 id。 */
    int getJob();

    /** 当前 HP / Max HP（v83 脚本兼容字段）。 */
    int getHp();

    int getMaxHp();

    /** 当前 MP / Max MP。 */
    int getMp();

    int getMaxMp();

    /** 当前地图 id。 */
    int getMapId();

    /** 角色 id。 */
    long getId();
}
