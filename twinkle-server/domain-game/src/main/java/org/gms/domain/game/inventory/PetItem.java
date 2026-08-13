package org.gms.domain.game.inventory;

import lombok.Getter;
import lombok.Setter;
import org.gms.i18n.I18n;

/**
 * 宠物现金物品实例。
 *
 * <p>宠物不是可堆叠普通物品：除 {@link Item} 的背包属性外，还携带会随运行期变化的
 * 名称、等级、亲密度和饱食度。状态放在稳定领域层，发包与数据库只消费投影，避免协议层
 * 或可替换逻辑层持有彼此的具体实现。
 */
@Getter
@Setter
public class PetItem extends Item {

    private String petName;
    private byte petLevel;
    private short closeness;
    private byte fullness;
    private short petAttribute;
    private short petSkill;
    private int remainLife;
    private short attribute;

    public PetItem(int itemId, int petId) {
        super(itemId);
        if (petId <= 0) {
            throw new IllegalArgumentException(I18n.message("error.pet.invalid_pet_id"));
        }
        setPetId(petId);
        this.petName = "";
        this.petLevel = 1;
        this.fullness = 100;
        this.remainLife = 18_000;
    }

    @Override
    public PetItem copy() {
        PetItem copy = new PetItem(getId(), getPetId());
        copyBase(copy);
        copy.petName = petName;
        copy.petLevel = petLevel;
        copy.closeness = closeness;
        copy.fullness = fullness;
        copy.petAttribute = petAttribute;
        copy.petSkill = petSkill;
        copy.remainLife = remainLife;
        copy.attribute = attribute;
        return copy;
    }
}
