package org.gms.channel;

import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapNpc;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.QuestSystem;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.resource.QuestResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NPC 交互端到端适配测试；WZ 测试数据自行构造，不包含参考项目资源。 */
public class NpcQuestGameplayTest {
    @Test public void shopValidatesOfferAndRangeAndCannotBuyAfterLeavingMap(@TempDir Path root) throws Exception {
        Path config = root.resolve("shops.properties"); Files.writeString(config, "2101=2000000:50\n");
        MapleMap map = new MapleMap(); map.setMapId(100);
        MapNpc npc = new MapNpc(99, 2101, 0, 0, 0, 0, 0, true); map.putNpc(npc);
        var session = new GameplayTestSession(1, map); session.character.setMeso(500);
        ItemData potion = new ItemData(2000000); potion.setPrice(25);
        var data = GameDataProvider.fixed(Map.of(2000000, potion, 2000001, new ItemData(2000001)), Map.of());
        var handler = new NpcShopHandler(new NpcShopCatalog(config.toString()), data, new ItemSystem(new DefaultVersionGate(), data));
        handler.handle(session, shop(0, 0, 2000000, 1));
        assertThat(session.character.getItemCount(2000000)).isZero();
        assertThat(handler.open(session, npc)).isTrue();
        handler.handle(session, shop(0, 0, 2000001, 1));
        assertThat(session.character.getMeso()).isEqualTo(500);
        handler.handle(session, shop(0, 0, 2000000, 2));
        assertThat(session.character.getMeso()).isEqualTo(400);
        handler.handle(session, shop(1, 1, 2000000, 1));
        assertThat(session.character.getMeso()).isEqualTo(425);
        assertThat(session.character.getItemCount(2000000)).isEqualTo(1);
        session.character.setX(400);
        handler.handle(session, shop(0, 0, 2000000, 1));
        assertThat(session.character.getMeso()).isEqualTo(425);
        session.character.setX(0); session.character.setMap(200);
        handler.handle(session, shop(0, 0, 2000000, 1));
        assertThat(session.character.getMeso()).isEqualTo(425);
    }

    @Test public void questRequiresNpcAndServerKillProgressAndAwardsExactlyOnce(@TempDir Path root) throws Exception {
        var resources = resources(root, "");
        MapleMap map = new MapleMap(); map.setMapId(100);
        map.putNpc(new MapNpc(99, 2101, 0, 0, 0, 0, 0, true));
        var session = new GameplayTestSession(1, map);
        var handler = new QuestActionHandler(resources, new QuestSystem(new DefaultVersionGate()));
        handler.handle(session, quest(1, 1000, 999));
        assertThat(session.character.getQuestStatus(1000)).isNull();
        handler.handle(session, quest(1, 1000, 2101));
        assertThat(session.character.getQuestStatus(1000).progressData()).isEqualTo("000");
        handler.handle(session, quest(2, 1000, 2101));
        assertThat(session.character.getQuestStatus(1000).getState()).isEqualTo(QuestStatus.State.STARTED);
        handler.killed(session, 100100); handler.killed(session, 100100); handler.killed(session, 100100);
        assertThat(session.character.getQuestStatus(1000).progressData()).isEqualTo("002");
        handler.handle(session, quest(2, 1000, 2101));
        handler.handle(session, quest(2, 1000, 2101));
        assertThat(session.character.getQuestStatus(1000).getState()).isEqualTo(QuestStatus.State.COMPLETED);
        assertThat(session.character.getMeso()).isEqualTo(20);
        assertThat(session.character.getExp()).isEqualTo(25);
    }

    @Test public void unsupportedQuestConditionIsNotSilentlyIgnored(@TempDir Path root) throws Exception {
        var resources = resources(root, "<int name=\"interval\" value=\"60\"/>");
        MapleMap map = new MapleMap(); map.putNpc(new MapNpc(99, 2101, 0, 0, 0, 0, 0, true));
        var session = new GameplayTestSession(1, map);
        var handler = new QuestActionHandler(resources, new QuestSystem(new DefaultVersionGate()));
        handler.handle(session, quest(1, 1000, 2101));
        assertThat(session.character.getQuestStatus(1000)).isNull();
    }

    private static WzResourceRegistry resources(Path root, String extraCondition) throws Exception {
        Path directory = Files.createDirectories(root.resolve("Quest.wz"));
        Files.writeString(directory.resolve("Check.img.xml"), """
                <imgdir name="Check.img"><imgdir name="1000">
                <imgdir name="0"><int name="npc" value="2101"/>%s</imgdir>
                <imgdir name="1"><int name="npc" value="2101"/>
                <imgdir name="mob"><imgdir name="0"><int name="id" value="100100"/><int name="count" value="2"/></imgdir></imgdir>
                </imgdir></imgdir></imgdir>
                """.formatted(extraCondition));
        Files.writeString(directory.resolve("Act.img.xml"), """
                <imgdir name="Act.img"><imgdir name="1000"><imgdir name="0"/>
                <imgdir name="1"><int name="money" value="20"/><int name="exp" value="25"/></imgdir></imgdir></imgdir>
                """);
        return new WzResourceRegistry(root, List.of(new QuestResourceLoader()), Runnable::run);
    }
    private static ByteArrayInPacket quest(int action, int questId, int npc) {
        var packet = new ByteArrayOutPacket(); packet.writeByte(action); packet.writeShort(questId); packet.writeInt(npc);
        return new ByteArrayInPacket(packet.getBytes());
    }
    private static ByteArrayInPacket shop(int mode, int slot, int itemId, int count) {
        var packet = new ByteArrayOutPacket(); packet.writeByte(mode); packet.writeShort(slot);
        packet.writeInt(itemId); packet.writeShort(count); return new ByteArrayInPacket(packet.getBytes());
    }
}
