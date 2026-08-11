package org.gms.domain.game.spi;

/**
 * 交易中的精确物品快照（稳定层 SPI）。
 *
 * <p>可替换交易逻辑只持有该不可变投影，不直接引用稳定层的 {@code Item}/{@code Equip}
 * 具体类。结算前由角色状态按背包类型、原槽位和全部实例属性重新核对，避免同 itemId
 * 的另一件装备或现金道具被误扣，也避免转移时丢失装备强化、所有者、过期时间等信息。
 */
public record TradeItemSnapshot(
        byte inventoryType,
        short sourcePosition,
        int itemId,
        int quantity,
        int cashId,
        int petId,
        String owner,
        int flag,
        long expiration,
        String giftFrom,
        EquipSnapshot equip,
        PetSnapshot pet) {

    /** 装备专有属性；普通物品的 {@link TradeItemSnapshot#equip()} 为 {@code null}。 */
    public record EquipSnapshot(
            byte upgradeSlots,
            short level,
            short str,
            short dex,
            short intStat,
            short luk,
            short hp,
            short mp,
            short watk,
            short matk,
            short wdef,
            short mdef,
            short acc,
            short avoid,
            short hands,
            short speed,
            short jump,
            byte vicious,
            byte itemLevel,
            long itemExp,
            int ringId) {
    }

    /** 宠物实例属性；普通物品与装备的 {@link TradeItemSnapshot#pet()} 为 {@code null}。 */
    public record PetSnapshot(
            String name,
            byte level,
            short closeness,
            byte fullness,
            short attribute,
            short skill,
            int remainLife,
            short itemAttribute) {
    }
}
