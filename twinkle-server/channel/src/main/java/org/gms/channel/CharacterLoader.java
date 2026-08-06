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
        chr.setAccountId(db.getAccountid());
        chr.setWorld(db.getWorld());
        chr.setName(db.getName());
        chr.setLevel(db.getLevel());
        chr.setExp(db.getExp());
        chr.setGachaExp(db.getGachaexp());
        chr.setStr(db.getStr());
        chr.setDex(db.getDex());
        chr.setLuk(db.getLuk());
        chr.setIntStat(db.getIntStat());
        chr.setHp(db.getHp());
        chr.setMp(db.getMp());
        chr.setMaxHp(db.getMaxhp());
        chr.setMaxMp(db.getMaxmp());
        chr.setMeso(db.getMeso());
        chr.setHpMpUsed(db.getHpMpUsed());
        chr.setJob(db.getJob());
        chr.setSkinColor(db.getSkincolor());
        chr.setGender(db.getGender());
        chr.setFame(db.getFame());
        chr.setFquest(db.getFquest());
        chr.setHair(db.getHair());
        chr.setFace(db.getFace());
        chr.setAp(db.getAp());
        chr.setSp(db.getSp());
        chr.setMap(db.getMap());
        chr.setSpawnPoint(db.getSpawnpoint());
        chr.setGm(db.getGm());
        chr.setParty(db.getParty());
        chr.setBuddyCapacity(db.getBuddyCapacity());
        chr.setCreateDate(db.getCreatedate());
        chr.setRank(db.getRank());
        chr.setRankMove(db.getRankMove());
        chr.setJobRank(db.getJobRank());
        chr.setJobRankMove(db.getJobRankMove());
        chr.setGuildId(db.getGuildid());
        chr.setGuildRank(db.getGuildrank());
        chr.setMessengerId(db.getMessengerid());
        chr.setMessengerPosition(db.getMessengerposition());
        chr.setMountLevel(db.getMountlevel());
        chr.setMountExp(db.getMountexp());
        chr.setMountTiredness(db.getMounttiredness());
        chr.setOmokWins(db.getOmokwins());
        chr.setOmokLosses(db.getOmoklosses());
        chr.setOmokTies(db.getOmokties());
        chr.setMatchCardWins(db.getMatchcardwins());
        chr.setMatchCardLosses(db.getMatchcardlosses());
        chr.setMatchCardTies(db.getMatchcardties());
        chr.setMerchantMesos(db.getMerchantMesos());
        chr.setHasMerchant(db.getHasMerchant() != 0);
        chr.setEquipSlots(db.getEquipslots());
        chr.setUseSlots(db.getUseslots());
        chr.setSetupSlots(db.getSetupslots());
        chr.setEtcSlots(db.getEtcslots());
        chr.setFamilyId(db.getFamilyId());
        chr.setMonsterBookCover(db.getMonsterbookcover());
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
        chr.setPqPoints(db.getPQPoints());
        chr.setDataString(db.getDataString());
        chr.setLastLogoutTime(db.getLastLogoutTime());
        chr.setLastExpGainTime(db.getLastExpGainTime());
        chr.setPartySearch(db.getPartySearch() != 0);
        chr.setJailExpire(db.getJailexpire());
        return chr;
    }
}
