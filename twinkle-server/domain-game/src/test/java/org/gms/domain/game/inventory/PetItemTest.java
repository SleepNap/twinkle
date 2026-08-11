package org.gms.domain.game.inventory;

import org.gms.domain.game.Character;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 宠物实例复制与交易投影测试。 */
class PetItemTest {

    @Test
    void rejectsMissingUniquePetId() {
        assertThatThrownBy(() -> new PetItem(5_000_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copyAndTradePreserveCompletePetState() {
        Character sender = character(1L);
        Character receiver = character(2L);
        PetItem pet = pet();
        sender.getInventory(InventoryType.CASH).putAtSlot((short) 2, pet);

        TradeItemSnapshot snapshot = sender.snapshotTradeItem((byte) 5, (short) 2, 1);

        assertThat(snapshot.pet()).isNotNull();
        assertThat(snapshot.pet().name()).isEqualTo("小黑");
        assertThat(sender.removeTradeItems(List.of(snapshot))).isTrue();
        assertThat(receiver.addTradeItems(List.of(snapshot), Map.of(5_000_000, 1))).isTrue();
        PetItem received = (PetItem) receiver.getInventory(InventoryType.CASH).getItem((short) 1);
        assertThat(received.getPetId()).isEqualTo(9001);
        assertThat(received.getPetName()).isEqualTo("小黑");
        assertThat(received.getPetLevel()).isEqualTo((byte) 12);
        assertThat(received.getCloseness()).isEqualTo((short) 3456);
        assertThat(received.getFullness()).isEqualTo((byte) 87);
        assertThat(received.getPetAttribute()).isEqualTo((short) 3);
        assertThat(received.getPetSkill()).isEqualTo((short) 4);
        assertThat(received.getRemainLife()).isEqualTo(17_500);
        assertThat(received.getAttribute()).isEqualTo((short) 5);
    }

    private static Character character(long id) {
        Character character = new Character(1L);
        character.setId(id);
        return character;
    }

    private static PetItem pet() {
        PetItem pet = new PetItem(5_000_000, 9001);
        pet.setPosition((short) 2);
        pet.setPetName("小黑");
        pet.setPetLevel((byte) 12);
        pet.setCloseness((short) 3456);
        pet.setFullness((byte) 87);
        pet.setPetAttribute((short) 3);
        pet.setPetSkill((short) 4);
        pet.setRemainLife(17_500);
        pet.setAttribute((short) 5);
        return pet;
    }
}
