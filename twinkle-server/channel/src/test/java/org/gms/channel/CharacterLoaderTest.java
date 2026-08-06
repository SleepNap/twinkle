package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        db.setAccountid(3L);
        db.setWorld(0);
        db.setName("Hero");
        db.setLevel(10);
        db.setExp(1234L);
        db.setGachaexp(5L);
        db.setStr((short) 40);
        db.setDex((short) 10);
        db.setLuk((short) 10);
        db.setIntStat((short) 10);
        db.setHp((short) 300);
        db.setMp((short) 100);
        db.setMaxhp((short) 320);
        db.setMaxmp((short) 110);
        db.setMeso(50000);
        db.setHpMpUsed(0);
        db.setJob(0);
        db.setSkincolor(0);
        db.setGender(1);
        db.setFame(3);
        db.setFquest(0);
        db.setHair(30000);
        db.setFace(20000);
        db.setAp(0);
        db.setSp("0,0,0,0,0,0,0,0,0,0");
        db.setMap(100000000);
        db.setSpawnpoint(0);
        db.setGm(0);
        db.setParty(0);
        db.setBuddyCapacity(25);
        db.setCreatedate("2026-08-06 00:00:00");
        db.setRank(1L);
        db.setRankMove(0);
        db.setJobRank(1L);
        db.setJobRankMove(0);
        db.setGuildid(0);
        db.setGuildrank(5);
        db.setMessengerid(0);
        db.setMessengerposition(4);
        db.setMountlevel(1);
        db.setMountexp(0);
        db.setMounttiredness(0);
        db.setOmokwins(0);
        db.setOmoklosses(0);
        db.setOmokties(0);
        db.setMatchcardwins(0);
        db.setMatchcardlosses(0);
        db.setMatchcardties(0);
        db.setMerchantMesos(0);
        db.setHasMerchant(1);
        db.setEquipslots(24);
        db.setUseslots(24);
        db.setSetupslots(24);
        db.setEtcslots(24);
        db.setFamilyId(-1);
        db.setMonsterbookcover(0);
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
        db.setPQPoints(0);
        db.setDataString("");
        db.setLastLogoutTime("2015-01-01 05:00:00");
        db.setLastExpGainTime("2015-01-01 05:00:00");
        db.setPartySearch(1);
        db.setJailexpire(0L);

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
}
