package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.VersionGate;

/**
 * 角色加载投影（data↔domain）：DB 存档 → 游戏内存态角色（架构 M2 进图）。
 *
 * <p>纯转换：从 data.Character（74 列标量）读出，填入 domain.Character（内存态权威，
 * Versioned 契约，红线 4 手动 new 不进容器）。构造时取当前逻辑版本（架构 5.3 版本门）。
 *
 * <p>背包/技能/keymap 等内存态不在此填充——对应存档表（inventoryitems 等）随 M2-2
 * 背包机制落地时建表并回填；进图最小切片先以空内存态进场。
 */
public final class CharacterLoader {

    private final VersionGate versionGate;

    public CharacterLoader(VersionGate versionGate) {
        this.versionGate = versionGate;
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
        // 加载态即"已落盘"：清除 setter 触发的脏标记（L4 增量 FLUSH 依据）
        chr.clearDirty();
        return chr;
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
