package org.gms.domain.script.host;

/**
 * 宿主对象契约 rm（reward manager，架构 M0 第 9 项）。
 *
 * <p>脚本通过 {@code rm} 给角色发奖（经验/金币/物品）。
 * 副作用经可替换层逻辑系统落盘（M2-2 背包机制），本契约只声明入口。
 */
public interface Rm {

    /** 加经验（amount ≥ 0）。 */
    void giveExp(int amount);

    /** 加金币。 */
    void giveMeso(int amount);
}
