package org.gms.wz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 装备 XML 在测试内独立构造，不读取参考项目资源。 */
public class EquipmentLoaderTest {
    @TempDir public Path root;

    @Test public void readsCharacterEquipmentRequirementsAndNormalizesInstanceDefaults() throws Exception {
        Path folder = Files.createDirectories(root.resolve("Character.wz/Cap"));
        Files.writeString(folder.resolve("01002000.img.xml"), """
                <imgdir name="01002000.img"><imgdir name="info">
                  <string name="islot" value="Cp"/><int name="reqLevel" value="12"/>
                  <int name="reqJob" value="5"/><int name="reqSTR" value="18"/>
                  <int name="reqDEX" value="9"/><int name="reqINT" value="3"/><int name="reqLUK" value="4"/>
                  <int name="reqPOP" value="7"/><int name="incSTR" value="2"/><int name="incMHP" value="45"/>
                  <int name="incPAD" value="6"/><int name="incMAD" value="8"/><int name="incPDD" value="11"/>
                  <int name="incMDD" value="13"/><int name="incCraft" value="3"/>
                  <int name="tuc" value="5"/><int name="equipTradeBlock" value="1"/><int name="onlyEquip" value="1"/>
                  <int name="price" value="17"/>
                </imgdir></imgdir>
                """);
        Files.writeString(folder.resolve("00002000.img.xml"), "<imgdir name=\"body\"/>");
        var items = new ItemLoader(root).loadAll();
        assertThat(items).containsOnlyKeys(1002000);
        var hat = items.get(1002000);
        assertThat(hat.getSlotMax()).isEqualTo(1);
        assertThat(hat.getPrice()).isEqualTo(17);
        assertThat(hat.getReqLevel()).isEqualTo(12);
        assertThat(hat.getEquipment().slot()).isEqualTo("Cp");
        assertThat(hat.getEquipment().requiredJob()).isEqualTo(5);
        assertThat(hat.getEquipment().requiredStr()).isEqualTo(18);
        assertThat(hat.getEquipment().requiredDex()).isEqualTo(9);
        assertThat(hat.getEquipment().requiredInt()).isEqualTo(3);
        assertThat(hat.getEquipment().requiredLuk()).isEqualTo(4);
        assertThat(hat.getEquipment().requiredFame()).isEqualTo(7);
        assertThat(hat.getEquipment().gender()).isEqualTo(2);
        assertThat(hat.getEquipment().bindOnEquip()).isTrue();
        assertThat(hat.getEquipment().uniqueEquipped()).isTrue();
        assertThat(hat.getEquipment().upgradeSlots()).isEqualTo(5);
        assertThat(hat.stats()).containsEntry("str", 2).containsEntry("hp", 45).containsEntry("watk", 6)
                .containsEntry("matk", 8).containsEntry("wdef", 11).containsEntry("mdef", 13).containsEntry("hands", 3);
    }

    @Test public void cashAndUnknownSlotsRemainExplicitWithoutTreatingAnimationsAsEquipment() throws Exception {
        Path folder = Files.createDirectories(root.resolve("Character.wz/Weapon"));
        Files.writeString(folder.resolve("01702000.img.xml"), """
                <imgdir name="01702000.img"><imgdir name="info"><int name="cash" value="1"/>
                <string name="islot" value="Wp"/></imgdir><imgdir name="stand1"/></imgdir>
                """);
        Files.writeString(folder.resolve("01302000.img.xml"), "<imgdir name=\"01302000.img\"><imgdir name=\"info\"/></imgdir>");
        var items = new ItemLoader(root).loadAll();
        assertThat(items).hasSize(2);
        assertThat(items.get(1702000).getEquipment().cash()).isTrue();
        assertThat(items.get(1302000).getEquipment().slot()).isEmpty();
    }
}
