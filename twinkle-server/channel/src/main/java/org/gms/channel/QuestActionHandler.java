package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.quest.QuestChange;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.v83.V83FileTime;
import org.gms.net.packet.v83.V83ItemSnapshot;
import org.gms.replaceable.QuestSystem;
import org.gms.wz.WzNode;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.WzResources;

import java.util.EnumMap;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 数据驱动的接取、完成与放弃；未知条件/奖励拒绝，不把客户端的完成请求当完成事实。 */
public final class QuestActionHandler implements PacketHandler {
    private final WzResourceRegistry resources;
    private final QuestSystem system;
    public QuestActionHandler(WzResourceRegistry resources, QuestSystem system) {
        this.resources = resources;
        this.system = system;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        try {
            PlayerCharacter character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 3) return;
            int action = packet.readByte(), questId = packet.readUnsignedShort();
            if (action < 1 || action > 3) return;
            synchronized (character) {
                var expected = action == 1 ? QuestStatus.State.NOT_STARTED : QuestStatus.State.STARTED;
                var target = action == 1 ? QuestStatus.State.STARTED : action == 2
                        ? QuestStatus.State.COMPLETED : QuestStatus.State.NOT_STARTED;
                WzNode check = stage("Check.img.xml", questId, action == 1 ? 0 : 1);
                if (check == null) return;
                Map<Integer, Integer> changes = new HashMap<>(), limits = new HashMap<>();
                int money = 0, experience = 0;
                if (action != 3) {
                    if (packet.available() < 4 || !valid(character, check, packet.readInt(), questId)) return;
                    WzNode reward = stage("Act.img.xml", questId, action == 1 ? 0 : 1);
                    if (reward != null) {
                        if (!Set.of("exp", "money", "nextQuest").containsAll(reward.values().keySet())
                                || !Set.of("item").containsAll(reward.children().keySet())) return;
                        money = reward.getInt("money").orElse(0);
                        experience = reward.getInt("exp").orElse(0);
                        if (experience < 0) return;
                        for (WzNode item : reward.child("item").map(WzNode::children).orElse(Map.of()).values()) {
                            if (!Set.of("id", "count").containsAll(item.values().keySet()) || !item.children().isEmpty()) return;
                            int itemId = item.getInt("id").orElse(0), count = item.getInt("count").orElse(0);
                            var definition = resources.item(itemId);
                            if (definition == null || Math.abs((long) count) > Short.MAX_VALUE) return;
                            changes.merge(itemId, count, Math::addExact);
                            limits.put(itemId, Math.min(Short.MAX_VALUE, definition.getSlotMax()));
                        }
                    }
                }
                Map<InventoryType, Map<Short, V83ItemSnapshot>> before = new EnumMap<>(InventoryType.class);
                for (var type : InventoryType.values()) if (type != InventoryType.UNDEFINED)
                    before.put(type, GameplayPackets.inventory(character, type));
                if (!system.apply(character, new QuestChange(questId, expected, target, changes, limits,
                        money, experience, System.currentTimeMillis()))) return;
                if (target == QuestStatus.State.STARTED) initializeMobProgress(character, questId);
                before.forEach((type, snapshot) -> GameplayPackets.inventoryChanges(type, snapshot,
                        GameplayPackets.inventory(character, type)).forEach(session::send));
                session.send(GameplayPackets.stats(Map.of(GameplayPackets.MESO, character.getMeso(),
                        GameplayPackets.EXP, (int) character.getExp())));
                sendStatus(session, character.getQuestStatus(questId));
            }
        } finally { session.send(GameplayPackets.enableActions()); }
    }

    private WzNode stage(String file, int id, int stage) {
        return resources.resource(WzResources.QUESTS).get(file).flatMap(root -> root.child(Integer.toString(id)))
                .flatMap(root -> root.child(Integer.toString(stage))).orElse(null);
    }

    private boolean valid(PlayerCharacter character, WzNode rule, int npcId, int questId) {
        if (!Set.of("npc", "lvmin", "lvmax").containsAll(rule.values().keySet())
                || !Set.of("job", "item", "quest", "mob").containsAll(rule.children().keySet())) return false;
        int expectedNpc = rule.getInt("npc").orElse(-1);
        if (npcId != expectedNpc || character.getMapObject().npcs().stream().noneMatch(npc ->
                npc.templateId() == npcId && GameplaySession.near(character, npc.x(), npc.y(), 300))) return false;
        if (character.getLevel() < rule.getInt("lvmin").orElse(0)
                || character.getLevel() > rule.getInt("lvmax").orElse(255)) return false;
        if (rule.child("job").isPresent() && rule.child("job").get().values().values().stream()
                .noneMatch(job -> job.equals(Integer.toString(character.getJob())))) return false;
        for (WzNode item : rule.child("item").map(WzNode::children).orElse(Map.of()).values()) {
            int id = item.getInt("id").orElse(0), count = item.getInt("count").orElse(-1);
            if (!Set.of("id", "count").containsAll(item.values().keySet()) || !item.children().isEmpty()
                    || id <= 0 || count < 0 || (count == 0 ? character.getItemCount(id) != 0 : character.getItemCount(id) < count)) return false;
        }
        for (WzNode requirement : rule.child("quest").map(WzNode::children).orElse(Map.of()).values()) {
            var status = character.getQuestStatus(requirement.getInt("id").orElse(0));
            int actual = status == null ? 0 : status.getState().ordinal();
            if (actual != requirement.getInt("state").orElse(-1)) return false;
        }
        var progress = character.getQuestStatus(questId);
        for (WzNode mob : rule.child("mob").map(WzNode::children).orElse(Map.of()).values()) {
            if (progress == null || progress.getProgress(mob.getInt("id").orElse(0)) < mob.getInt("count").orElse(Integer.MAX_VALUE))
                return false;
        }
        return true;
    }

    public static void sendStatus(PacketSession session, QuestStatus status) {
        var packet = GameplayPackets.packet(SendOpcode.SHOW_STATUS_INFO);
        packet.writeByte(1);
        packet.writeShort(status.getQuestId());
        packet.writeByte(status.getState().ordinal());
        if (status.getState() == QuestStatus.State.STARTED) packet.writeString(status.progressData());
        if (status.getState() == QuestStatus.State.COMPLETED) packet.writeLong(V83FileTime.encode(status.getCompletionTime()));
        session.send(packet);
    }

    public void killed(PacketSession session, int mobId) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null) return;
        synchronized (character) {
            for (var quest : character.quests().values()) {
                if (quest.getState() != QuestStatus.State.STARTED) continue;
                WzNode rule = stage("Check.img.xml", quest.getQuestId(), 1);
                if (rule == null) continue;
                boolean changed = false;
                for (WzNode mob : rule.child("mob").map(WzNode::children).orElse(Map.of()).values()) {
                    int id = mob.getInt("id").orElse(0), cap = mob.getInt("count").orElse(0);
                    if (id == mobId && quest.getProgress(id) < cap) {
                        if (system.setProgress(character, quest.getQuestId(), id, quest.getProgress(id) + 1)) {
                            quest.setProgressText(id, String.format(Locale.ROOT, "%03d", quest.getProgress(id)));
                            changed = true;
                        }
                    }
                }
                if (changed) sendStatus(session, quest);
            }
        }
    }

    private void initializeMobProgress(PlayerCharacter character, int questId) {
        WzNode completion = stage("Check.img.xml", questId, 1);
        if (completion == null) return;
        completion.child("mob").ifPresent(mobs -> mobs.children().entrySet().stream()
                .filter(entry -> entry.getKey().matches("[0-9]+"))
                .sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey())))
                .forEach(entry -> character.getQuestStatus(questId)
                        .setProgressText(entry.getValue().getInt("id").orElse(0), "000")));
    }
}
