package org.gms.channel;

import org.gms.data.entity.InventoryItemEntity;
import org.gms.data.entity.QuestProgressEntity;
import org.gms.data.entity.QuestStatusEntity;
import org.gms.data.entity.SkillEntity;
import org.gms.data.repo.InventoryItemRepository;
import org.gms.data.repo.QuestProgressSnapshot;
import org.gms.data.repo.QuestRepository;
import org.gms.data.repo.SkillRepository;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.PetItem;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色加载投影：data.Character（DB 存档）→ domain.Character（内存态角色）。
 * 覆盖 74 列标量映射、布尔列转换、逻辑版本来源。
 */
class CharacterLoaderTest {

    private final CharacterLoader loader = new CharacterLoader(new DefaultVersionGate());

    @Test
    @DisplayName("DB 存档投影到内存态角色：标量 + 布尔转换 + 逻辑版本")
    void projectsAllScalarFields() {
        org.gms.data.entity.Character db = new org.gms.data.entity.Character();
        db.setId(7L);
        db.setAccountId(3L);
        db.setWorld(0);
        db.setName("Hero");
        db.setLevel(10);
        db.setExp(1234L);
        db.setGachaExp(5L);
        db.setStr((short) 40);
        db.setDex((short) 10);
        db.setLuk((short) 10);
        db.setIntStat((short) 10);
        db.setHp((short) 300);
        db.setMp((short) 100);
        db.setMaxHp((short) 320);
        db.setMaxMp((short) 110);
        db.setMeso(50000);
        db.setHpMpUsed(0);
        db.setJob(0);
        db.setSkinColor(0);
        db.setGender(1);
        db.setFame(3);
        db.setFQuest(0);
        db.setHair(30000);
        db.setFace(20000);
        db.setAp(0);
        db.setSp("0,0,0,0,0,0,0,0,0,0");
        db.setMap(100000000);
        db.setSpawnPoint(0);
        db.setGm(0);
        db.setParty(0);
        db.setBuddyCapacity(25);
        db.setCreateDate("2026-08-06 00:00:00");
        db.setRank(1L);
        db.setRankMove(0);
        db.setJobRank(1L);
        db.setJobRankMove(0);
        db.setGuildId(0);
        db.setGuildRank(5);
        db.setMessengerId(0);
        db.setMessengerPosition(4);
        db.setMountLevel(1);
        db.setMountExp(0);
        db.setMountTiredness(0);
        db.setOmokWins(0);
        db.setOmokLosses(0);
        db.setOmokTies(0);
        db.setMatchCardWins(0);
        db.setMatchCardLosses(0);
        db.setMatchCardTies(0);
        db.setMerchantMesos(0);
        db.setHasMerchant(1);
        db.setEquipSlots(24);
        db.setUseSlots(24);
        db.setSetupSlots(24);
        db.setEtcSlots(24);
        db.setFamilyId(-1);
        db.setMonsterBookCover(0);
        db.setAllianceRank(5);
        db.setVanquisherStage(0);
        db.setAriantPoints(0);
        db.setDojoPoints(0);
        db.setLastDojoStage(0);
        db.setFinishedDojoTutorial(0);
        db.setVanquisherKills(0);
        db.setSummonValue(0);
        db.setPartnerId(0);
        db.setMarriageItemId(0);
        db.setReborns(0);
        db.setPqPoints(0);
        db.setDataString("");
        db.setLastLogoutTime("2015-01-01 05:00:00");
        db.setLastExpGainTime("2015-01-01 05:00:00");
        db.setPartySearch(1);
        db.setJailExpire(0L);

        Character chr = loader.fromData(db);

        // 标量映射
        assertThat(chr.getId()).isEqualTo(7L);
        assertThat(chr.getAccountId()).isEqualTo(3L);
        assertThat(chr.getName()).isEqualTo("Hero");
        assertThat(chr.getLevel()).isEqualTo(10);
        assertThat(chr.getExp()).isEqualTo(1234L);
        assertThat(chr.getStr()).isEqualTo((short) 40);
        assertThat(chr.getDex()).isEqualTo((short) 10);
        assertThat(chr.getHp()).isEqualTo(300);
        assertThat(chr.getMp()).isEqualTo(100);
        assertThat(chr.getMaxHp()).isEqualTo(320);
        assertThat(chr.getMaxMp()).isEqualTo(110);
        assertThat(chr.getMeso()).isEqualTo(50000);
        assertThat(chr.getGender()).isEqualTo(1);
        assertThat(chr.getMap()).isEqualTo(100000000);
        assertThat(chr.getSpawnPoint()).isZero();
        assertThat(chr.getBuddyCapacity()).isEqualTo(25);
        assertThat(chr.getGuildRank()).isEqualTo(5);
        assertThat(chr.getMountLevel()).isEqualTo(1);
        assertThat(chr.getFamilyId()).isEqualTo(-1);
        assertThat(chr.getEquipSlots()).isEqualTo(24);
        assertThat(chr.getDataString()).isEmpty();

        // 布尔列：DB 0/1 → boolean（Lombok 对 boolean 字段生成 isXxx()）
        assertThat(chr.isHasMerchant()).isTrue();
        assertThat(chr.isPartySearch()).isTrue();
        assertThat(chr.isFinishedDojoTutorial()).isFalse();

        // 逻辑版本取当前版本门（首版 = 1）
        assertThat(chr.logicVersion()).isEqualTo(DefaultVersionGate.INITIAL_VERSION);
    }

    @Test
    @DisplayName("DB 背包投影全部物品：装备保留负槽，普通物品进入对应背包")
    void projectsAllInventoryItems() {
        InventoryItemEntity equipped = inventoryItem(1, 1040002, 1, -5, 1);
        equipped.setCashId(7001);
        equipped.setUpgradeSlots(5);
        equipped.setStr(12);
        equipped.setWatk(3);
        equipped.setItemLevel(4);
        equipped.setItemExp(4567L);
        InventoryItemEntity potion = inventoryItem(2, 2000000, 2, 3, 25);
        potion.setOwner("Hero");
        InventoryItemEntity pet = inventoryItem(3, 5000000, 5, 2, 1);
        pet.setPetId(9001);
        pet.setPetName("小黑");
        pet.setPetLevel(12);
        pet.setPetCloseness(3456);
        pet.setPetFullness(87);
        pet.setPetAttribute(3);
        pet.setPetSkill(4);
        pet.setPetRemainLife(17_500);
        pet.setItemAttribute(5);
        InventoryItemRepository repository = new InventoryItemRepository() {
            @Override
            public List<InventoryItemEntity> findByCharacterId(long characterId) {
                return List.of(equipped, potion, pet);
            }

            @Override
            public void insert(InventoryItemEntity item) {
            }

            @Override
            public void replaceAll(long characterId, List<InventoryItemEntity> items) {
            }
        };
        CharacterLoader inventoryLoader = new CharacterLoader(new DefaultVersionGate(), repository);
        org.gms.data.entity.Character db = new org.gms.data.entity.Character();
        db.setId(9L);
        db.setName("Hero");
        db.setEquipSlots(24);
        db.setUseSlots(24);
        db.setSetupSlots(24);
        db.setEtcSlots(24);

        Character chr = inventoryLoader.fromData(db);

        Equip loadedEquip = (Equip) chr.getInventory(InventoryType.EQUIP).getItem((short) -5);
        assertThat(loadedEquip).isInstanceOf(Equip.class);
        assertThat(loadedEquip.getCashId()).isEqualTo(7001);
        assertThat(loadedEquip.getUpgradeSlots()).isEqualTo((byte) 5);
        assertThat(loadedEquip.getStr()).isEqualTo((short) 12);
        assertThat(loadedEquip.getWatk()).isEqualTo((short) 3);
        assertThat(loadedEquip.getItemLevel()).isEqualTo((byte) 4);
        assertThat(loadedEquip.getItemExp()).isEqualTo(4567L);
        assertThat(chr.getInventory(InventoryType.USE).getItem((short) 3).getId()).isEqualTo(2000000);
        assertThat(chr.getInventory(InventoryType.USE).getItem((short) 3).getQuantity()).isEqualTo((short) 25);
        assertThat(chr.getInventory(InventoryType.USE).getItem((short) 3).getOwner()).isEqualTo("Hero");
        PetItem loadedPet = (PetItem) chr.getInventory(InventoryType.CASH).getItem((short) 2);
        assertThat(loadedPet.getPetId()).isEqualTo(9001);
        assertThat(loadedPet.getPetName()).isEqualTo("小黑");
        assertThat(loadedPet.getPetLevel()).isEqualTo((byte) 12);
        assertThat(loadedPet.getCloseness()).isEqualTo((short) 3456);
        assertThat(loadedPet.getFullness()).isEqualTo((byte) 87);
        assertThat(loadedPet.getRemainLife()).isEqualTo(17_500);
        assertThat(chr.isDirty()).isFalse();

        List<InventoryItemEntity> snapshot = inventoryLoader.toInventoryData(chr);
        assertThat(snapshot).hasSize(3);
        InventoryItemEntity savedEquip = snapshot.stream()
                .filter(item -> item.getPosition() == -5)
                .findFirst()
                .orElseThrow();
        assertThat(savedEquip.getType()).isEqualTo(1);
        assertThat(savedEquip.getCashId()).isEqualTo(7001);
        assertThat(savedEquip.getUpgradeSlots()).isEqualTo(5);
        assertThat(savedEquip.getStr()).isEqualTo(12);
        assertThat(savedEquip.getWatk()).isEqualTo(3);
        assertThat(savedEquip.getItemLevel()).isEqualTo(4);
        assertThat(savedEquip.getItemExp()).isEqualTo(4567L);
        InventoryItemEntity savedPet = snapshot.stream()
                .filter(item -> item.getPetId() == 9001)
                .findFirst()
                .orElseThrow();
        assertThat(savedPet.getType()).isEqualTo(3);
        assertThat(savedPet.getPetName()).isEqualTo("小黑");
        assertThat(savedPet.getPetLevel()).isEqualTo(12);
        assertThat(savedPet.getPetCloseness()).isEqualTo(3456);
        assertThat(savedPet.getPetFullness()).isEqualTo(87);
        assertThat(savedPet.getPetAttribute()).isEqualTo(3);
        assertThat(savedPet.getPetSkill()).isEqualTo(4);
        assertThat(savedPet.getPetRemainLife()).isEqualTo(17_500);
        assertThat(savedPet.getItemAttribute()).isEqualTo(5);
    }

    @Test
    @DisplayName("DB 任务状态与进度完整回填，并可重建关联投影")
    void projectsQuestStatusesAndProgress() {
        QuestStatusEntity status = new QuestStatusEntity();
        status.setQuestStatusId(77L);
        status.setCharacterId(9);
        status.setQuest(1000);
        status.setStatus(QuestStatus.State.STARTED.ordinal());
        status.setTime(1_700_000_000);
        status.setExpires(1_800_000_000_000L);
        status.setForfeited(2);
        status.setCompleted(3);
        status.setInfo(2000);
        QuestProgressEntity progress = new QuestProgressEntity();
        progress.setCharacterId(9);
        progress.setQuestStatusId(77);
        progress.setProgressId(100_001);
        progress.setProgress("003");
        QuestRepository repository = new QuestRepository() {
            @Override
            public List<QuestStatusEntity> findStatusesByCharacterId(long characterId) {
                return List.of(status);
            }

            @Override
            public List<QuestProgressEntity> findProgressByCharacterId(long characterId) {
                return List.of(progress);
            }

            @Override
            public void replaceAll(long characterId, List<QuestStatusEntity> statuses,
                                   List<QuestProgressSnapshot> progressSnapshots) {
            }
        };
        CharacterLoader questLoader = new CharacterLoader(
                new DefaultVersionGate(), null, repository);
        org.gms.data.entity.Character db = new org.gms.data.entity.Character();
        db.setId(9L);
        db.setName("Hero");

        Character chr = questLoader.fromData(db);

        QuestStatus loaded = chr.getQuestStatus(1000);
        assertThat(loaded.getState()).isEqualTo(QuestStatus.State.STARTED);
        assertThat(loaded.getCompletionTime()).isEqualTo(1_700_000_000_000L);
        assertThat(loaded.getExpirationTime()).isEqualTo(1_800_000_000_000L);
        assertThat(loaded.getForfeited()).isEqualTo(2);
        assertThat(loaded.getCompleted()).isEqualTo(3);
        assertThat(loaded.getInfoNumber()).isEqualTo(2000);
        assertThat(loaded.progressData()).isEqualTo("003");
        assertThat(loaded.getProgress(100_001)).isEqualTo(3);

        assertThat(questLoader.toQuestStatusData(chr)).singleElement().satisfies(saved -> {
            assertThat(saved.getQuest()).isEqualTo(1000);
            assertThat(saved.getStatus()).isEqualTo(QuestStatus.State.STARTED.ordinal());
            assertThat(saved.getTime()).isEqualTo(1_700_000_000);
            assertThat(saved.getInfo()).isEqualTo(2000);
        });
        assertThat(questLoader.toQuestProgressData(chr)).containsExactly(
                new QuestProgressSnapshot(1000, 100_001, "003"));
        assertThat(chr.isDirty()).isFalse();
    }

    @Test
    @DisplayName("DB 技能等级、四转上限和期限完整回填并重建快照")
    void projectsSkills() {
        SkillEntity saved = new SkillEntity();
        saved.setCharacterId(9);
        saved.setSkillId(1_121_000);
        saved.setSkillLevel(10);
        saved.setMasterLevel(20);
        saved.setExpiration(1_800_000_000_000L);
        SkillRepository repository = new SkillRepository() {
            @Override
            public List<SkillEntity> findByCharacterId(long characterId) {
                return List.of(saved);
            }

            @Override
            public void replaceAll(long characterId, List<SkillEntity> skills) {
            }
        };
        CharacterLoader skillLoader = new CharacterLoader(
                new DefaultVersionGate(), null, null, repository);
        org.gms.data.entity.Character db = new org.gms.data.entity.Character();
        db.setId(9L);
        db.setName("Hero");

        Character chr = skillLoader.fromData(db);

        assertThat(chr.getSkill(1_121_000)).isEqualTo(
                new SkillEntry(1_121_000, 10, 20, 1_800_000_000_000L));
        assertThat(skillLoader.toSkillData(chr)).singleElement().satisfies(projected -> {
            assertThat(projected.getCharacterId()).isEqualTo(9);
            assertThat(projected.getSkillId()).isEqualTo(1_121_000);
            assertThat(projected.getSkillLevel()).isEqualTo(10);
            assertThat(projected.getMasterLevel()).isEqualTo(20);
            assertThat(projected.getExpiration()).isEqualTo(1_800_000_000_000L);
        });
        assertThat(chr.isDirty()).isFalse();
    }

    private static InventoryItemEntity inventoryItem(int type, int itemId, int inventoryType,
                                                      int position, int quantity) {
        InventoryItemEntity item = new InventoryItemEntity();
        item.setType(type);
        item.setItemId(itemId);
        item.setInventoryType(inventoryType);
        item.setPosition(position);
        item.setQuantity(quantity);
        item.setOwner("");
        item.setGiftFrom("");
        return item;
    }
}
