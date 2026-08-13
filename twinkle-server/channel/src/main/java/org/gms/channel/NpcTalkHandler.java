package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.Character;
import org.gms.domain.script.ConversationScript;
import org.gms.domain.script.ScriptManager;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.QuestSystem;

import java.util.Map;

/**
 * NPC 对话发起（RecvOpcode.NPC_TALK）。
 *
 * <p>v83 收包 = {@code int oid}（地图对象 id，此处直接作 npcId 用）。流程：
 * 打开对话脚本（key {@code "nps/{npcId}"}）→ 会话与宿主存 session attr →
 * invoke {@code start()}。经典脚本走 start+action，nextlevel 脚本走 start+levelXxx。
 *
 * <p>对话会话（{@link ConversationScript}）与宿主（{@link NpcConversationHost}）
 * 存 session attr（连接级状态）；多轮由 {@link NpcTalkMoreHandler} 恢复。
 */
@Log4j2
public final class NpcTalkHandler implements PacketHandler {



    private final ScriptManager scriptManager;
    private final ItemSystem itemSystem;
    private final QuestSystem questSystem;

    public NpcTalkHandler(ScriptManager scriptManager, ItemSystem itemSystem, QuestSystem questSystem) {
        this.scriptManager = scriptManager;
        this.itemSystem = itemSystem;
        this.questSystem = questSystem;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.npc.talk.outside_stage"));
            return;
        }
        Character chr = session.getAttr("character");
        if (chr == null) {
            session.close(I18n.message("error.npc.talk.not_in_map"));
            return;
        }
        if (session.getAttr("npcConversation") != null) {
            return;     // 已在对话中，忽略重复
        }
        int npcId = packet.readInt();
        NpcConversationHost host = new NpcConversationHost(session, chr, npcId,
                itemSystem, questSystem, () -> closeConversation(session));
        ConversationScript script = scriptManager.openConversation("nps/" + npcId, Map.of("cm", host));
        if (script == null) {
            log.warn(I18n.message("log.npc.script_missing"), npcId);
            return;
        }
        session.setAttr("npcConversation", script);
        session.setAttr("npcHost", host);
        // start() 可能不存在（纯发包脚本），容忍
        try {
            script.invoke("start");
        } catch (RuntimeException e) {
            log.error(I18n.message("log.npc.start_failed"), npcId, e);
            closeConversation(session);
            return;
        }
        // start() 内可能已 dispose：invoke 返回后关会话
        if (host.isDisposed()) {
            closeConversation(session);
        }
    }

    /** 关闭对话会话（dispose / 脚本异常 / 断链）。 */
    public static void closeConversation(PacketSession session) {
        ConversationScript script = session.getAttr("npcConversation");
        if (script != null) {
            script.close();
            session.setAttr("npcConversation", null);
        }
        NpcConversationHost host = session.getAttr("npcHost");
        if (host != null) {
            session.setAttr("npcHost", null);
        }
    }
}
