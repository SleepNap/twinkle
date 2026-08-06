package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.domain.script.host.Cm;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.QuestSystem;

/**
 * NPC 对话宿主（channel 实现 {@link Cm} 契约，M3-5）。
 *
 * <p>持有：发包出口 {@link PacketSession} + 角色（经 {@link Character} 具体类——host
 * 是稳定层内部适配，可替换层逻辑系统仍经 spi 接口，红线 11 针对"可替换层不得引用
 * 具体类"，host 在 channel 属装配层）、能力经 {@link ItemSystem}/{@link QuestSystem}
 * （版本门）。
 *
 * <p>nextlevel 路由字段 {@link #context} 存于宿主，由 NpcTalkMoreHandler 双路由消费。
 * 文本输入结果 {@link #getText} 存宿主。
 */
public final class NpcConversationHost implements Cm {

    private final PacketSession session;
    private final Character chr;
    private final int npcId;
    private final ItemSystem itemSystem;
    private final QuestSystem questSystem;
    private final Runnable onDispose;

    /** nextlevel 路由上下文（经典 status 脚本为 null）。 */
    private NextLevelContext context;
    /** 文本输入结果（msgType 2 回包写入）。 */
    private String getText;
    /** 是否已请求关闭对话（dispose 调用标记；实际关闭由 handler 在 invoke 返回后执行）。 */
    private volatile boolean disposed;

    public NpcConversationHost(PacketSession session, Character chr, int npcId,
                               ItemSystem itemSystem, QuestSystem questSystem, Runnable onDispose) {
        this.session = session;
        this.chr = chr;
        this.npcId = npcId;
        this.itemSystem = itemSystem;
        this.questSystem = questSystem;
        this.onDispose = onDispose;
    }

    /** 当前 npc 模板 id。 */
    public int npcId() {
        return npcId;
    }

    /** 当前路由上下文。 */
    public NextLevelContext context() {
        return context;
    }

    /** 是否已请求关闭对话。 */
    public boolean isDisposed() {
        return disposed;
    }

    /** 写入文本输入结果。 */
    public void setText(String text) {
        this.getText = text;
    }

    /** 文本输入结果（msgType 2 回包写入，handler 读取传给脚本）。 */
    public String getText() {
        return getText;
    }

    /** 经典脚本发普通对话会清路由（回到 status 重入）。 */
    private void clearRoute() {
        context = null;
    }

    /* ---------- 只读角色信息 ---------- */

    @Override public String getName() { return chr.getName(); }
    @Override public int getLevel() { return chr.getLevel(); }
    @Override public int getJob() { return chr.getJob(); }
    @Override public int getHp() { return chr.getHp(); }
    @Override public int getMaxHp() { return chr.getMaxHp(); }
    @Override public int getMp() { return chr.getMp(); }
    @Override public int getMaxMp() { return chr.getMaxMp(); }
    @Override public int getMapId() { return chr.getMap(); }
    @Override public long getId() { return chr.getId(); }

    /* ---------- 对话（发包 + 路由管理） ---------- */

    @Override
    public void sendOk(String text) {
        clearRoute();
        session.send(NpcPacketFactory.talk(npcId, text, 1));
    }

    @Override
    public void sendNext(String text) {
        clearRoute();
        session.send(NpcPacketFactory.talk(npcId, text, 2));
    }

    @Override
    public void sendPrev(String text) {
        clearRoute();
        session.send(NpcPacketFactory.talk(npcId, text, 3));
    }

    @Override
    public void sendNextPrev(String text) {
        clearRoute();
        session.send(NpcPacketFactory.talk(npcId, text, 4));
    }

    @Override
    public void sendYesNo(String text) {
        clearRoute();
        session.send(NpcPacketFactory.yesNo(npcId, text));
    }

    @Override
    public void sendAcceptDecline(String text) {
        clearRoute();
        session.send(NpcPacketFactory.acceptDecline(npcId, text));
    }

    @Override
    public void sendSimple(String text) {
        clearRoute();
        // 选项数按 #L 计数（简化：脚本文本里 #L..#l 即选项）
        session.send(NpcPacketFactory.simple(npcId, text, countOptions(text)));
    }

    @Override
    public void sendStyle(String text, int[] styles) {
        clearRoute();
        session.send(NpcPacketFactory.style(npcId, text, styles));
    }

    @Override
    public void sendGetText(String text) {
        clearRoute();
        session.send(NpcPacketFactory.getText(npcId, text, ""));
    }

    @Override
    public void sendGetNumber(String text, int def, int min, int max) {
        clearRoute();
        session.send(NpcPacketFactory.getNumber(npcId, text, def, min, max));
    }

    @Override
    public void dispose() {
        // 只标记关闭请求；实际关闭（关 Context）由 handler 在 invoke 返回后执行，
        // 避免在 JS 执行栈内关闭正在运行的 Context（会抛异常/断连）。
        disposed = true;
    }

    /* ---------- nextlevel 变体（发包 + 写路由） ---------- */

    @Override
    public void sendNextLevel(String nextLevel, String text) {
        session.send(NpcPacketFactory.talk(npcId, text, 2));
        context = new NextLevelContext("SEND_NEXT", null, nextLevel, null);
    }

    @Override
    public void sendLastLevel(String lastLevel, String text) {
        session.send(NpcPacketFactory.talk(npcId, text, 3));
        context = new NextLevelContext("SEND_LAST", lastLevel, null, null);
    }

    @Override
    public void sendLastNextLevel(String lastLevel, String nextLevel, String text) {
        session.send(NpcPacketFactory.talk(npcId, text, 4));
        context = new NextLevelContext("SEND_LAST_NEXT", lastLevel, nextLevel, null);
    }

    @Override
    public void sendOkLevel(String nextLevel, String text) {
        session.send(NpcPacketFactory.talk(npcId, text, 1));
        context = new NextLevelContext("SEND_OK", null, nextLevel, null);
    }

    @Override
    public void sendSelectLevel(String text) {
        session.send(NpcPacketFactory.simple(npcId, text, countOptions(text)));
        context = new NextLevelContext("SEND_SELECT", null, null, "");
    }

    @Override
    public void sendSelectLevel(String prefix, String text) {
        session.send(NpcPacketFactory.simple(npcId, text, countOptions(text)));
        context = new NextLevelContext("SEND_SELECT", null, null, prefix);
    }

    @Override
    public void sendNextSelectLevel(String nextLevel, String text) {
        session.send(NpcPacketFactory.simple(npcId, text, countOptions(text)));
        context = new NextLevelContext("SEND_NEXT_SELECT", null, nextLevel, null);
    }

    @Override
    public void getInputNumberLevel(String nextLevel, String text, int def, int min, int max) {
        session.send(NpcPacketFactory.getNumber(npcId, text, def, min, max));
        context = new NextLevelContext("GET_INPUT_NUMBER", null, nextLevel, null);
    }

    @Override
    public void getInputTextLevel(String nextLevel, String text) {
        session.send(NpcPacketFactory.getText(npcId, text, ""));
        context = new NextLevelContext("GET_INPUT_TEXT", null, nextLevel, null);
    }

    @Override
    public void sendAcceptDeclineLevel(String decLevel, String acceptLevel, String text) {
        session.send(NpcPacketFactory.acceptDecline(npcId, text));
        context = new NextLevelContext("SEND_ACCEPT_DECLINE", decLevel, acceptLevel, null);
    }

    @Override
    public void sendYesNoLevel(String noLevel, String yesLevel, String text) {
        session.send(NpcPacketFactory.yesNo(npcId, text));
        context = new NextLevelContext("SEND_YES_NO", noLevel, yesLevel, null);
    }

    /* ---------- 能力（经 system，版本门） ---------- */

    @Override
    public void giveItem(int itemId, int quantity) {
        itemSystem.giveItem(chr, itemId, quantity);
    }

    @Override
    public void takeItem(int itemId, int quantity) {
        itemSystem.takeItem(chr, itemId, quantity);
    }

    @Override
    public int getItemQuantity(int itemId) {
        return itemSystem.countItem(chr, itemId);
    }

    @Override
    public boolean haveItem(int itemId, int quantity) {
        return itemSystem.countItem(chr, itemId) >= quantity;
    }

    @Override
    public void gainExp(int amount) {
        chr.setExp(chr.getExp() + amount);
    }

    @Override
    public void gainMeso(int amount) {
        chr.setMeso(chr.getMeso() + amount);
    }

    @Override
    public void startQuest(int questId) {
        questSystem.startQuest(chr, questId);
    }

    @Override
    public void completeQuest(int questId) {
        questSystem.completeQuest(chr, questId);
    }

    @Override
    public int getQuestStatus(int questId) {
        var qs = chr.getQuestStatus(questId);
        if (qs == null) {
            return 0;
        }
        return switch (qs.getState()) {
            case STARTED -> 1;
            case COMPLETED -> 2;
            default -> 0;
        };
    }

    @Override
    public void warp(int mapId) {
        chr.setMap(mapId);
    }

    /** 统计选项数（msgType 4 用，脚本 {@code #L..#l} 标记）。 */
    private static int countOptions(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("#L", idx)) >= 0) {
            count++;
            idx += 2;
        }
        return count;
    }

    /** 供 handler 关闭会话时取发包出口。 */
    public PacketSession session() {
        return session;
    }
}
