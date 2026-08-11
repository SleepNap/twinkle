package org.gms.net.packet.v83;

/** v83 宠物物品的可变实例状态投影。 */
public record V83PetStats(
        String name,
        int level,
        int closeness,
        int fullness,
        int attribute,
        int skill,
        int remainLife,
        int itemAttribute) {

    public V83PetStats {
        name = name == null ? "" : name;
    }
}
