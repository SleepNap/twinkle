package org.gms.domain.game.map;

/** 地图 NPC 的 WZ 投影；对象 ID 与模板 ID 分离，运行态属于频道地图。 */
public record MapNpc(int objectId, int templateId, int x, int y, int foothold,
                     int left, int right, boolean facingLeft) {
}
