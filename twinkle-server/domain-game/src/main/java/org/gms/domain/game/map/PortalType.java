package org.gms.domain.game.map;

/**
 * 传送点类型（v83 协议字节值，红线 1：字节级兼容）。思路参考自 BeiDou-Server Portal.PortalType。
 *
 * <p>只列 v83 常用类型；其余用 {@link #UNKNOWN} 兜底（解析 WZ 时按 type 值映射，未知不回崩）。
 */
public enum PortalType {

    /** 地图间传送门。 */
    MAP_PORTAL((byte) 0),
    /** 门（技能/物品开的门）。 */
    DOOR((byte) 1),
    /** 移动。 */
    MOVE((byte) 2),
    /** 换图。 */
    CHANGE((byte) 3),
    /** 隐形传送点。 */
    INVISIBLE((byte) 4),
    /** 城镇传送点。 */
    TOWN_PORTAL((byte) 6),
    /** 城镇坐标点。 */
    TOWN_POINT((byte) 7),
    /** 脚本传送点（触发脚本）。 */
    SCRIPT((byte) 8),
    /** 脚本传送点 2。 */
    SCRIPT2((byte) 9),
    /** 弹簧。 */
    SPRING((byte) 11),
    /** 未识别。 */
    UNKNOWN((byte) -1);

    private final byte type;

    private PortalType(byte type) {
        this.type = type;
    }

    public byte getType() {
        return type;
    }

    /** 按 v83 字节值取类型，未知回 {@link #UNKNOWN}。 */
    public static PortalType fromType(byte type) {
        for (PortalType p : values()) {
            if (p.type == type) {
                return p;
            }
        }
        return UNKNOWN;
    }
}
