package org.gms.domain.game.map;

import lombok.Getter;
import lombok.Setter;

/**
 * 地图物理边界线段（纯数据，稳定层）。Map.wz 的 foothold 节点，
 * 进图/移动的物理基础（角色站立/落地的地面线）。
 *
 * <p>WZ 结构三级：layer（层）→ group（组）→ foothold（段）。同一组内相邻段用
 * prev/next 串成链，线段的 x1/y1 → x2/y2 方向与 WZ 数据一致，语义（是否可站/可跳）
 * 由消费方（移动/落点逻辑）判定，本类只持有数据。Lombok 生成 getter/setter（红线 11）。
 */
@Getter
@Setter
public class MapleFoothold {

    /** 组内序号（WZ foothold 节点名）。 */
    private final int id;
    /** 所属层（WZ 第一层 layer）。 */
    private final int layer;
    /** 所属组（WZ 第二层 group）。 */
    private final int group;

    /** 线段起点。 */
    private int x1;
    private int y1;
    /** 线段终点。 */
    private int x2;
    private int y2;
    /** 同组内前一个段 id；无则 -1。 */
    private int prev = -1;
    /** 同组内下一个段 id；无则 -1。 */
    private int next = -1;
    /** 强制落地 y（0 = 无强制；部分地图有，如 103040000）。 */
    private int force;

    public MapleFoothold(int id, int layer, int group) {
        this.id = id;
        this.layer = layer;
        this.group = group;
    }
}
