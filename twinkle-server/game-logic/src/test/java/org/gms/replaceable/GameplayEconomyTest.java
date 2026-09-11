package org.gms.replaceable;

import org.gms.domain.game.logic.*;
import org.gms.logic.game.*;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.quest.QuestChange;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillDefinition;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实角色聚合上的经济与成长回归，验证失败不产生部分写入。 */
public class GameplayEconomyTest {
    private final DefaultVersionGate versions = new DefaultVersionGate();
    private final PlayerCharacter character = new PlayerCharacter(versions.currentVersion());
    private final ItemSystem items = new DefaultItemSystem(versions,
            GameDataProvider.fixed(Map.of(2000000, potion()), Map.of()));

    private static ItemData potion() {
        ItemData data = new ItemData(2000000);
        data.setSlotMax(100);
        data.setPrice(25);
        data.putStat("hp", 50);
        return data;
    }

    @Test public void buyChecksCostOverflowAndCapacityBeforeDeductingMoney() {
        character.setMeso(1000);
        assertThat(items.buy(character, 2000000, 100, Integer.MAX_VALUE)).isEqualTo(ItemSystem.ShopResult.NO_MONEY);
        assertThat(items.buy(character, 2000000, -1, 50)).isEqualTo(ItemSystem.ShopResult.INVALID);
        assertThat(items.buy(character, 2000000, 2, 50)).isEqualTo(ItemSystem.ShopResult.SUCCESS);
        assertThat(character.getMeso()).isEqualTo(900);
        assertThat(character.getItemCount(2000000)).isEqualTo(2);
        assertThat(items.giveItem(character, 2000000, 2398)).isTrue();
        assertThat(items.buy(character, 2000000, 1, 50)).isEqualTo(ItemSystem.ShopResult.NO_SPACE);
        assertThat(character.getMeso()).isEqualTo(900);
    }

    @Test public void healingConsumesOnlyRequestedSlotAndRejectsStaleVersion() {
        items.giveItem(character, 2000000, 101);
        character.setHp(1);
        character.setMaxHp(100);
        assertThat(items.consumeRecovery(character, (short) 2, 2000000, 1000)).isTrue();
        assertThat(character.getInventory(InventoryType.USE).getItem((short) 1).getQuantity()).isEqualTo((short) 100);
        assertThat(character.getInventory(InventoryType.USE).getItem((short) 2)).isNull();
        assertThat(character.getHp()).isEqualTo(51);
        versions.onReload();
        assertThat(items.consumeRecovery(character, (short) 1, 2000000, 1000)).isFalse();
        assertThat(character.getHp()).isEqualTo(51);
    }

    @Test public void inventorySplitAndSwapPreserveInstanceMetadata() {
        items.giveItem(character, 2000000, 10);
        var inventory = character.getInventory(InventoryType.USE);
        inventory.getItem((short) 1).setOwner("甲");
        assertThat(items.moveItem(character, (byte) 2, (short) 1, (short) 2, 3)).isTrue();
        assertThat(inventory.getItem((short) 2).getOwner()).isEqualTo("甲");
        inventory.getItem((short) 2).setOwner("乙");
        assertThat(items.moveItem(character, (byte) 2, (short) 1, (short) 2, 2)).isFalse();
        assertThat(items.moveItem(character, (byte) 2, (short) 1, (short) 2, 7)).isTrue();
        assertThat(inventory.getItem((short) 1).getOwner()).isEqualTo("乙");
        assertThat(character.getItemCount(2000000)).isEqualTo(10);
    }

    @Test public void buyingPermanentItemsDoesNotExtendExpiredOrOwnedStacks() {
        items.giveItem(character, 2000000, 1);
        var inventory = character.getInventory(InventoryType.USE);
        inventory.getItem((short) 1).setExpiration(1000);
        inventory.getItem((short) 1).setOwner("旧实例");
        character.setMeso(50);
        assertThat(items.buy(character, 2000000, 1, 50)).isEqualTo(ItemSystem.ShopResult.SUCCESS);
        assertThat(inventory.getItem((short) 1).getQuantity()).isEqualTo((short) 1);
        assertThat(inventory.getItem((short) 2).getExpiration()).isEqualTo(-1);
    }

    @Test public void questRewardIsAtomicAndCannotBeReplayed() {
        character.startQuest(1000);
        items.giveItem(character, 2000000, 2400);
        QuestChange reward = new QuestChange(1000, QuestStatus.State.STARTED, QuestStatus.State.COMPLETED,
                Map.of(2000000, -1, 2000001, 1), Map.of(2000001, 100), 50, 20, 1000);
        QuestSystem quests = new DefaultQuestSystem(versions);
        assertThat(quests.apply(character, reward)).isFalse();
        assertThat(character.getItemCount(2000000)).isEqualTo(2400);
        assertThat(character.getQuestStatus(1000).getState()).isEqualTo(QuestStatus.State.STARTED);
        items.takeItem(character, 2000000, 100);
        assertThat(quests.apply(character, reward)).isTrue();
        assertThat(quests.apply(character, reward)).isFalse();
        assertThat(character.getItemCount(2000000)).isEqualTo(2299);
        assertThat(character.getItemCount(2000001)).isEqualTo(1);
        assertThat(character.getMeso()).isEqualTo(50);
    }

    @Test public void apAndSpRejectOverspendingAndForeignSkillBranches() {
        ProgressionSystem progression = new DefaultProgressionSystem(versions);
        character.setAp(5);
        int original = character.getStrStat();
        assertThat(progression.allocateAp(character, Map.of(0x40, 4, 0x80, 2))).isFalse();
        assertThat(character.getAp()).isEqualTo(5);
        assertThat(progression.allocateAp(character, Map.of(0x40, 5))).isTrue();
        assertThat(character.getStrStat()).isEqualTo((short) (original + 5));
        assertThat(progression.allocateAp(character, Map.of(0x40, 1))).isFalse();
        character.setJob(110);
        character.setLevel(30);
        character.setSp("1");
        assertThat(progression.allocateSp(character, new SkillDefinition(2001002, 20, 1, Map.of()))).isFalse();
        SkillDefinition skill = new SkillDefinition(1101004, 20, 1, Map.of(1001003, 3));
        assertThat(progression.allocateSp(character, skill)).isFalse();
        character.putSkill(new SkillEntry(1001003, 3, 0, -1));
        assertThat(progression.allocateSp(character, skill)).isTrue();
        assertThat(progression.allocateSp(character, skill)).isFalse();
        assertThat(character.getSp()).isEqualTo("0");
        assertThat(character.getSkill(1101004).level()).isEqualTo(1);
    }
}
