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
import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.inventory.PetItem;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.hotreload.versioned.VersionGate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色加载投影（data↔domain）：DB 存档 → 游戏内存态角色（架构 M2 进图）。
 *
 * <p>纯转换：从 data.Character（74 列标量）读出，填入 domain.Character（内存态权威，
 * Versioned 契约，红线 4 手动 new 不进容器）。构造时取当前逻辑版本（架构 5.3 版本门）。
 *
 * <p>inventory_items 全部投影进对应 domain 背包，供进图背包、交易和运行时物品逻辑共用；
 * 已穿戴装备仍以 EQUIP 背包负槽位表达。
 */
public final class CharacterLoader {

    private final VersionGate versionGate;
    private final InventoryItemRepository inventoryItemRepository;
    private final QuestRepository questRepository;
    private final SkillRepository skillRepository;

    public CharacterLoader(VersionGate versionGate) {
        this(versionGate, null, null, null);
    }

    public CharacterLoader(VersionGate versionGate, InventoryItemRepository inventoryItemRepository) {
        this(versionGate, inventoryItemRepository, null, null);
    }

    public CharacterLoader(VersionGate versionGate, InventoryItemRepository inventoryItemRepository,
                           QuestRepository questRepository) {
        this(versionGate, inventoryItemRepository, questRepository, null);
    }

    public CharacterLoader(VersionGate versionGate, InventoryItemRepository inventoryItemRepository,
                           QuestRepository questRepository, SkillRepository skillRepository) {
        this.versionGate = versionGate;
        this.inventoryItemRepository = inventoryItemRepository;
        this.questRepository = questRepository;
        this.skillRepository = skillRepository;
    }

    /** data.Character（DB 存档）→ domain.Character（内存态角色）。 */
    public Character fromData(org.gms.data.entity.Character db) {
        Character chr = new Character(versionGate.currentVersion());
        // ---- 74 列标量映射（红线 3：存档格式兼容，newmaple 表结构不变） ----
        chr.setId(db.getId());
        chr.setAccountId(db.getAccountId());
        chr.setWorld(db.getWorld());
        chr.setName(db.getName());
        chr.setLevel(db.getLevel());
        chr.setExp(db.getExp());
        chr.setGachaExp(db.getGachaExp());
        chr.setStr(db.getStr());
        chr.setDex(db.getDex());
        chr.setLuk(db.getLuk());
        chr.setIntStat(db.getIntStat());
        chr.setHp(db.getHp());
        chr.setMp(db.getMp());
        chr.setMaxHp(db.getMaxHp());
        chr.setMaxMp(db.getMaxMp());
        chr.setMeso(db.getMeso());
        chr.setHpMpUsed(db.getHpMpUsed());
        chr.setJob(db.getJob());
        chr.setSkinColor(db.getSkinColor());
        chr.setGender(db.getGender());
        chr.setFame(db.getFame());
        chr.setFquest(db.getFQuest());
        chr.setHair(db.getHair());
        chr.setFace(db.getFace());
        chr.setAp(db.getAp());
        chr.setSp(db.getSp());
        chr.setMap(db.getMap());
        chr.setSpawnPoint(db.getSpawnPoint());
        chr.setGm(db.getGm());
        chr.setParty(db.getParty());
        chr.setBuddyCapacity(db.getBuddyCapacity());
        chr.setCreateDate(db.getCreateDate());
        chr.setRank(db.getRank());
        chr.setRankMove(db.getRankMove());
        chr.setJobRank(db.getJobRank());
        chr.setJobRankMove(db.getJobRankMove());
        chr.setGuildId(db.getGuildId());
        chr.setGuildRank(db.getGuildRank());
        chr.setMessengerId(db.getMessengerId());
        chr.setMessengerPosition(db.getMessengerPosition());
        chr.setMountLevel(db.getMountLevel());
        chr.setMountExp(db.getMountExp());
        chr.setMountTiredness(db.getMountTiredness());
        chr.setOmokWins(db.getOmokWins());
        chr.setOmokLosses(db.getOmokLosses());
        chr.setOmokTies(db.getOmokTies());
        chr.setMatchCardWins(db.getMatchCardWins());
        chr.setMatchCardLosses(db.getMatchCardLosses());
        chr.setMatchCardTies(db.getMatchCardTies());
        chr.setMerchantMesos(db.getMerchantMesos());
        chr.setHasMerchant(db.getHasMerchant() != 0);
        chr.setEquipSlots(db.getEquipSlots());
        chr.setUseSlots(db.getUseSlots());
        chr.setSetupSlots(db.getSetupSlots());
        chr.setEtcSlots(db.getEtcSlots());
        chr.setFamilyId(db.getFamilyId());
        chr.setMonsterBookCover(db.getMonsterBookCover());
        chr.setAllianceRank(db.getAllianceRank());
        chr.setVanquisherStage(db.getVanquisherStage());
        chr.setAriantPoints(db.getAriantPoints());
        chr.setDojoPoints(db.getDojoPoints());
        chr.setLastDojoStage(db.getLastDojoStage());
        chr.setFinishedDojoTutorial(db.getFinishedDojoTutorial() != 0);
        chr.setVanquisherKills(db.getVanquisherKills());
        chr.setSummonValue(db.getSummonValue());
        chr.setPartnerId(db.getPartnerId());
        chr.setMarriageItemId(db.getMarriageItemId());
        chr.setReborns(db.getReborns());
        chr.setPqPoints(db.getPqPoints());
        chr.setDataString(db.getDataString());
        chr.setLastLogoutTime(db.getLastLogoutTime());
        chr.setLastExpGainTime(db.getLastExpGainTime());
        chr.setPartySearch(db.getPartySearch() != 0);
        chr.setJailExpire(db.getJailExpire());
        // 全部背包：inventory_items → domain 各类型背包；负槽装备仍进入 EQUIP
        if (inventoryItemRepository != null) {
            for (InventoryItemEntity e : inventoryItemRepository.findByCharacterId(db.getId())) {
                InventoryType inventoryType = InventoryType.getByType((byte) e.getInventoryType());
                if (e.getPosition() < 0) {
                    inventoryType = InventoryType.EQUIP;
                } else if (inventoryType == InventoryType.UNDEFINED) {
                    inventoryType = ItemConstants.getInventoryType(e.getItemId());
                }
                if (inventoryType == InventoryType.UNDEFINED) {
                    continue;
                }
                Item item;
                if (e.getType() == 1 || inventoryType == InventoryType.EQUIP) {
                    item = toEquip(e);
                } else if (e.getType() == 3 || e.getPetId() > 0) {
                    item = toPet(e);
                } else {
                    item = new Item(e.getItemId());
                }
                item.setPosition((short) e.getPosition());
                item.setQuantity((short) e.getQuantity());
                item.setOwner(e.getOwner());
                item.setPetId(e.getPetId());
                item.setCashId(e.getCashId());
                item.setFlag(e.getFlag());
                item.setExpiration(e.getExpiration());
                item.setGiftFrom(e.getGiftFrom());
                Inventory inventory = chr.getInventory(inventoryType);
                inventory.putAtSlot((short) e.getPosition(), item);
            }
        }
        loadQuests(chr, db.getId());
        if (skillRepository != null) {
            for (SkillEntity skill : skillRepository.findByCharacterId(db.getId())) {
                chr.putSkill(new SkillEntry(skill.getSkillId(), skill.getSkillLevel(),
                        skill.getMasterLevel(), skill.getExpiration()));
            }
        }
        // 加载态即"已落盘"：清除 setter 触发的脏标记（L4 增量 FLUSH 依据）
        chr.clearDirty();
        return chr;
    }

    private void loadQuests(Character chr, long characterId) {
        if (questRepository == null) {
            return;
        }
        Map<Long, QuestStatus> byStatusId = new HashMap<>();
        for (QuestStatusEntity entity : questRepository.findStatusesByCharacterId(characterId)) {
            if (entity.getStatus() < 0 || entity.getStatus() >= QuestStatus.State.values().length) {
                continue;
            }
            QuestStatus status = new QuestStatus(entity.getQuest());
            status.setState(QuestStatus.State.values()[entity.getStatus()]);
            if (entity.getTime() > -1) {
                status.setCompletionTime(entity.getTime() * 1000L);
            }
            status.setExpirationTime(entity.getExpires());
            status.setForfeited(entity.getForfeited());
            status.setCompleted(entity.getCompleted());
            status.setInfoNumber(entity.getInfo());
            chr.putQuest(status);
            if (entity.getQuestStatusId() != null) {
                byStatusId.put(entity.getQuestStatusId(), status);
            }
        }
        for (QuestProgressEntity entity : questRepository.findProgressByCharacterId(characterId)) {
            QuestStatus status = byStatusId.get((long) entity.getQuestStatusId());
            if (status != null) {
                status.setProgressText(entity.getProgressId(), entity.getProgress());
            }
        }
    }

    private static Equip toEquip(InventoryItemEntity e) {
        Equip equip = new Equip(e.getItemId());
        equip.setUpgradeSlots((byte) e.getUpgradeSlots());
        equip.setLevel((short) e.getLevel());
        equip.setStr((short) e.getStr());
        equip.setDex((short) e.getDex());
        equip.setIntStat((short) e.getIntStat());
        equip.setLuk((short) e.getLuk());
        equip.setHp((short) e.getHp());
        equip.setMp((short) e.getMp());
        equip.setWatk((short) e.getWatk());
        equip.setMatk((short) e.getMatk());
        equip.setWdef((short) e.getWdef());
        equip.setMdef((short) e.getMdef());
        equip.setAcc((short) e.getAcc());
        equip.setAvoid((short) e.getAvoid());
        equip.setHands((short) e.getHands());
        equip.setSpeed((short) e.getSpeed());
        equip.setJump((short) e.getJump());
        equip.setVicious((byte) e.getVicious());
        equip.setItemLevel((byte) e.getItemLevel());
        equip.setItemExp(e.getItemExp());
        equip.setRingId(e.getRingId());
        return equip;
    }

    private static PetItem toPet(InventoryItemEntity entity) {
        PetItem pet = new PetItem(entity.getItemId(), entity.getPetId());
        pet.setPetName(entity.getPetName());
        pet.setPetLevel((byte) entity.getPetLevel());
        pet.setCloseness((short) entity.getPetCloseness());
        pet.setFullness((byte) entity.getPetFullness());
        pet.setPetAttribute((short) entity.getPetAttribute());
        pet.setPetSkill((short) entity.getPetSkill());
        pet.setRemainLife(entity.getPetRemainLife());
        pet.setAttribute((short) entity.getItemAttribute());
        return pet;
    }

    /** 构建全部五类背包的完整数据库快照。 */
    public List<InventoryItemEntity> toInventoryData(Character chr) {
        List<InventoryItemEntity> result = new ArrayList<>();
        for (InventoryType inventoryType : InventoryType.values()) {
            if (inventoryType == InventoryType.UNDEFINED) {
                continue;
            }
            for (Item item : chr.getInventory(inventoryType).items()) {
                InventoryItemEntity entity = new InventoryItemEntity();
                entity.setType(item instanceof Equip ? 1 : item instanceof PetItem ? 3 : 2);
                entity.setCharacterId(Math.toIntExact(chr.getId()));
                entity.setAccountId(chr.getAccountId() == null ? 0 : Math.toIntExact(chr.getAccountId()));
                entity.setItemId(item.getId());
                entity.setInventoryType(inventoryType.getType());
                entity.setPosition(item.getPosition());
                entity.setQuantity(item.getQuantity());
                entity.setOwner(item.getOwner() == null ? "" : item.getOwner());
                entity.setPetId(item.getPetId());
                entity.setCashId(item.getCashId());
                entity.setFlag(item.getFlag());
                entity.setExpiration(item.getExpiration());
                entity.setGiftFrom(item.getGiftFrom() == null ? "" : item.getGiftFrom());
                if (item instanceof Equip equip) {
                    copyEquipStats(equip, entity);
                } else if (item instanceof PetItem pet) {
                    copyPetStats(pet, entity);
                }
                result.add(entity);
            }
        }
        return List.copyOf(result);
    }

    /** 构建全部任务状态的数据库快照。 */
    public List<QuestStatusEntity> toQuestStatusData(Character chr) {
        List<QuestStatusEntity> result = new ArrayList<>();
        for (QuestStatus status : chr.quests().values()) {
            QuestStatusEntity entity = new QuestStatusEntity();
            entity.setCharacterId(Math.toIntExact(chr.getId()));
            entity.setQuest(status.getQuestId());
            entity.setStatus(status.getState().ordinal());
            entity.setTime(status.getCompletionTime() > 0
                    ? Math.toIntExact(status.getCompletionTime() / 1000L) : -1);
            entity.setExpires(status.getExpirationTime());
            entity.setForfeited(status.getForfeited());
            entity.setCompleted(status.getCompleted());
            entity.setInfo(status.getInfoNumber());
            result.add(entity);
        }
        result.sort(java.util.Comparator.comparingInt(QuestStatusEntity::getQuest));
        return List.copyOf(result);
    }

    /** 构建任务进度投影；仓储插入状态后再解析为真实 quest_status_id。 */
    public List<QuestProgressSnapshot> toQuestProgressData(Character chr) {
        List<QuestProgressSnapshot> result = new ArrayList<>();
        List<QuestStatus> statuses = new ArrayList<>(chr.quests().values());
        statuses.sort(java.util.Comparator.comparingInt(QuestStatus::getQuestId));
        for (QuestStatus status : statuses) {
            for (Map.Entry<Integer, String> entry : status.progress().entrySet()) {
                result.add(new QuestProgressSnapshot(
                        status.getQuestId(), entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(result);
    }

    /** 构建全部技能的数据库快照。 */
    public List<SkillEntity> toSkillData(Character chr) {
        List<SkillEntity> result = new ArrayList<>();
        List<SkillEntry> skills = new ArrayList<>(chr.skills().values());
        skills.sort(java.util.Comparator.comparingInt(SkillEntry::skillId));
        for (SkillEntry skill : skills) {
            SkillEntity entity = new SkillEntity();
            entity.setCharacterId(Math.toIntExact(chr.getId()));
            entity.setSkillId(skill.skillId());
            entity.setSkillLevel(skill.level());
            entity.setMasterLevel(skill.masterLevel());
            entity.setExpiration(skill.expiration());
            result.add(entity);
        }
        return List.copyOf(result);
    }

    private static void copyEquipStats(Equip equip, InventoryItemEntity entity) {
        entity.setUpgradeSlots(equip.getUpgradeSlots());
        entity.setLevel(equip.getLevel());
        entity.setStr(equip.getStr());
        entity.setDex(equip.getDex());
        entity.setIntStat(equip.getIntStat());
        entity.setLuk(equip.getLuk());
        entity.setHp(equip.getHp());
        entity.setMp(equip.getMp());
        entity.setWatk(equip.getWatk());
        entity.setMatk(equip.getMatk());
        entity.setWdef(equip.getWdef());
        entity.setMdef(equip.getMdef());
        entity.setAcc(equip.getAcc());
        entity.setAvoid(equip.getAvoid());
        entity.setHands(equip.getHands());
        entity.setSpeed(equip.getSpeed());
        entity.setJump(equip.getJump());
        entity.setVicious(equip.getVicious());
        entity.setItemLevel(equip.getItemLevel());
        entity.setItemExp(equip.getItemExp());
        entity.setRingId(equip.getRingId());
    }

    private static void copyPetStats(PetItem pet, InventoryItemEntity entity) {
        entity.setPetName(pet.getPetName() == null ? "" : pet.getPetName());
        entity.setPetLevel(pet.getPetLevel());
        entity.setPetCloseness(pet.getCloseness());
        entity.setPetFullness(pet.getFullness());
        entity.setPetAttribute(pet.getPetAttribute());
        entity.setPetSkill(pet.getPetSkill());
        entity.setPetRemainLife(pet.getRemainLife());
        entity.setItemAttribute(pet.getAttribute());
    }

    /** domain.Character（内存态权威）→ data.Character（DB 存档，L4 增量 FLUSH 落库用）。 */
    public org.gms.data.entity.Character toData(Character chr) {
        org.gms.data.entity.Character db = new org.gms.data.entity.Character();
        db.setId(chr.getId());
        db.setAccountId(chr.getAccountId());
        db.setWorld(chr.getWorld());
        db.setName(chr.getName());
        db.setLevel(chr.getLevel());
        db.setExp(chr.getExp());
        db.setGachaExp(chr.getGachaExp());
        db.setStr(chr.getStr());
        db.setDex(chr.getDex());
        db.setLuk(chr.getLuk());
        db.setIntStat(chr.getIntStat());
        db.setHp((short) chr.getHp());
        db.setMp((short) chr.getMp());
        db.setMaxHp((short) chr.getMaxHp());
        db.setMaxMp((short) chr.getMaxMp());
        db.setMeso(chr.getMeso());
        db.setHpMpUsed(chr.getHpMpUsed());
        db.setJob(chr.getJob());
        db.setSkinColor(chr.getSkinColor());
        db.setGender(chr.getGender());
        db.setFame(chr.getFame());
        db.setFQuest(chr.getFquest());
        db.setHair(chr.getHair());
        db.setFace(chr.getFace());
        db.setAp(chr.getAp());
        db.setSp(chr.getSp());
        db.setMap(chr.getMap());
        db.setSpawnPoint(chr.getSpawnPoint());
        db.setGm(chr.getGm());
        db.setParty(chr.getParty());
        db.setBuddyCapacity(chr.getBuddyCapacity());
        db.setCreateDate(chr.getCreateDate());
        db.setRank(chr.getRank());
        db.setRankMove(chr.getRankMove());
        db.setJobRank(chr.getJobRank());
        db.setJobRankMove(chr.getJobRankMove());
        db.setGuildId(chr.getGuildId());
        db.setGuildRank(chr.getGuildRank());
        db.setMessengerId(chr.getMessengerId());
        db.setMessengerPosition(chr.getMessengerPosition());
        db.setMountLevel(chr.getMountLevel());
        db.setMountExp(chr.getMountExp());
        db.setMountTiredness(chr.getMountTiredness());
        db.setOmokWins(chr.getOmokWins());
        db.setOmokLosses(chr.getOmokLosses());
        db.setOmokTies(chr.getOmokTies());
        db.setMatchCardWins(chr.getMatchCardWins());
        db.setMatchCardLosses(chr.getMatchCardLosses());
        db.setMatchCardTies(chr.getMatchCardTies());
        db.setMerchantMesos(chr.getMerchantMesos());
        db.setHasMerchant(chr.isHasMerchant() ? 1 : 0);
        db.setEquipSlots(chr.getEquipSlots());
        db.setUseSlots(chr.getUseSlots());
        db.setSetupSlots(chr.getSetupSlots());
        db.setEtcSlots(chr.getEtcSlots());
        db.setFamilyId(chr.getFamilyId());
        db.setMonsterBookCover(chr.getMonsterBookCover());
        db.setAllianceRank(chr.getAllianceRank());
        db.setVanquisherStage(chr.getVanquisherStage());
        db.setAriantPoints(chr.getAriantPoints());
        db.setDojoPoints(chr.getDojoPoints());
        db.setLastDojoStage(chr.getLastDojoStage());
        db.setFinishedDojoTutorial(chr.isFinishedDojoTutorial() ? 1 : 0);
        db.setVanquisherKills(chr.getVanquisherKills());
        db.setSummonValue(chr.getSummonValue());
        db.setPartnerId(chr.getPartnerId());
        db.setMarriageItemId(chr.getMarriageItemId());
        db.setReborns(chr.getReborns());
        db.setPqPoints(chr.getPqPoints());
        db.setDataString(chr.getDataString());
        db.setLastLogoutTime(chr.getLastLogoutTime());
        db.setLastExpGainTime(chr.getLastExpGainTime());
        db.setPartySearch(chr.isPartySearch() ? 1 : 0);
        db.setJailExpire(chr.getJailExpire());
        return db;
    }
}
