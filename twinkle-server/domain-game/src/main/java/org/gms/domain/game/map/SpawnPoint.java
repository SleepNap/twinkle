package org.gms.domain.game.map;

import lombok.Getter;
import lombok.Setter;

/**
 * 怪物刷新点（纯数据，稳定层）。WZ 加载填充（M2-4）。
 * Lombok 生成 getter/setter（红线 11）。
 */
@Getter
@Setter
public class SpawnPoint {

    private final int monsterId;
    private final int x;
    private final int y;
    /** 重生间隔（毫秒）。 */
    private int respawnInterval;
    /** 刷新概率（0-100，受怪物容量/倍率限制时按此抽签）。 */
    private int chance;

    public SpawnPoint(int monsterId, int x, int y) {
        this.monsterId = monsterId;
        this.x = x;
        this.y = y;
    }
}
