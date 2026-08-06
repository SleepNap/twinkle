package org.gms.domain.game.map;

import lombok.Getter;
import lombok.Setter;

/**
 * 传送点（纯数据，稳定层）。WZ 加载填充（M2-4），v83 字段对齐（红线 1 字节级兼容）。
 * Lombok 生成 getter/setter（红线 11）。
 */
@Getter
@Setter
public class Portal {

    private int id;
    private String name;
    private PortalType type;
    private int x;
    private int y;
    /** 目标地图 id（换图类传送点用）。 */
    private int targetMapId;
    /** 目标传送点名（换图类传送点用）。 */
    private String targetPortalName;
    private boolean script;

    public Portal(int id, String name, PortalType type, int x, int y) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.x = x;
        this.y = y;
    }
}
