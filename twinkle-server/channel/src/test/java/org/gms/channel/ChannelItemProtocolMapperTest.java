package org.gms.channel;

import org.gms.domain.game.inventory.PetItem;
import org.gms.net.packet.v83.V83ItemSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 频道领域物品到 v83 协议投影的适配测试。 */
class ChannelItemProtocolMapperTest {

    @Test
    void petUsesPetIdAsProtocolUniqueIdAndCarriesState() {
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

        V83ItemSnapshot snapshot = ChannelItemProtocolMapper.toSnapshot(pet);

        assertThat(snapshot.itemType()).isEqualTo(2);
        assertThat(snapshot.cashId()).isEqualTo(9001L);
        assertThat(snapshot.cash()).isTrue();
        assertThat(snapshot.petStats()).isNotNull();
        assertThat(snapshot.petStats().name()).isEqualTo("小黑");
        assertThat(snapshot.petStats().level()).isEqualTo(12);
        assertThat(snapshot.petStats().closeness()).isEqualTo(3456);
        assertThat(snapshot.petStats().fullness()).isEqualTo(87);
        assertThat(snapshot.petStats().attribute()).isEqualTo(3);
        assertThat(snapshot.petStats().skill()).isEqualTo(4);
        assertThat(snapshot.petStats().remainLife()).isEqualTo(17_500);
        assertThat(snapshot.petStats().itemAttribute()).isEqualTo(5);
    }
}
