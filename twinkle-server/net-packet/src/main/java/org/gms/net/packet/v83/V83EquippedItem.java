package org.gms.net.packet.v83;

/**
 * v83 角色外观中的一件已穿戴物品。
 *
 * @param position 持久化槽位，已穿戴装备使用负数（例如 -5、-11）
 * @param itemId 物品模板 id
 */
public record V83EquippedItem(int position, int itemId) {
}
