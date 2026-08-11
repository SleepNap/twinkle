package org.gms.net.packet.v83;

import java.util.List;

/**
 * v83 角色外观协议投影，与登录/频道业务模型解耦。
 */
public record V83CharacterLook(
        int gender,
        int skinColor,
        int face,
        int hair,
        List<V83EquippedItem> equippedItems) {

    public V83CharacterLook {
        equippedItems = equippedItems == null ? List.of() : List.copyOf(equippedItems);
    }
}
