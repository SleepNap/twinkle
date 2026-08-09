package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.script.ConversationScript;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * NPC 对话继续（RecvOpcode.NPC_TALK_MORE）——双路由（思路参考自 BeiDou-Server 的
 * NPCScriptManager.action/nextLevel，实现自研）。
 *
 * <p>v83 收包 = {@code byte lastMsg} + {@code byte action} + [lastMsg==2 → readString 文本；
 * 否则 readInt/readByte 选择]。action：1=下一步/是、0=否/返回、-1=关闭。
 *
 * <p>路由：会话路由上下文 levelType 非 null → 按 nextlevel 类型派发 {@code level{xxx}}；
 * 否则经典 {@code action(mode, type, selection)} 重入（脚本内 status 推进）。
 */
@Log4j2
public final class NpcTalkMoreHandler implements PacketHandler {



    private static final byte MODE_NEXT = 1;
    private static final byte MODE_BACK = 0;
    private static final byte MODE_CLOSE = -1;

    public NpcTalkMoreHandler() {
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close("阶段外收到 NPC 对话继续");
            return;
        }
        ConversationScript script = session.getAttr("npcConversation");
        NpcConversationHost host = session.getAttr("npcHost");
        if (script == null || host == null) {
            return;     // 无对话上下文，忽略
        }
        byte lastMsg = packet.readByte();
        byte mode = packet.readByte();
        int selection;
        String inputText = null;
        if (lastMsg == 2) {
            inputText = packet.readString();
            selection = -1;
            host.setText(inputText);
        } else if (packet.available() >= 4) {
            selection = packet.readInt();
        } else if (packet.available() >= 1) {
            selection = packet.readByte();
        } else {
            selection = 0;
        }

        NextLevelContext ctx = host.context();
        if (ctx != null && ctx.levelType != null) {
            // nextlevel 派发
            nextLevel(script, host, ctx, mode, selection);
        } else {
            // 经典 status 重入
            try {
                script.invoke("action", mode, (int) lastMsg, selection);
            } catch (RuntimeException e) {
                log.error("NPC 对话 action() 失败", e);
                NpcTalkHandler.closeConversation(session);
            }
        }
        // 脚本内可能已 dispose：invoke 返回后关会话（不在 JS 栈内关 Context）
        if (host.isDisposed()) {
            NpcTalkHandler.closeConversation(session);
        }
    }

    /** nextlevel 函数派发（参考 BeiDou NextLevelType 派发规则，自研实现）。 */
    private void nextLevel(ConversationScript script, NpcConversationHost host,
                           NextLevelContext ctx, byte mode, int selection) {
        if (mode == MODE_CLOSE) {
            host.dispose();
            return;
        }
        switch (ctx.levelType) {
            case "SEND_SELECT" -> {
                if (mode == MODE_BACK) {
                    host.dispose();
                } else {
                    invokeOrDispose(script, host, "level" + ctx.prefix + selection);
                }
            }
            case "GET_INPUT_NUMBER", "SEND_NEXT_SELECT" -> {
                if (mode == MODE_BACK) {
                    host.dispose();
                } else {
                    invokeOrDispose(script, host, "level" + ctx.nextLevel, selection);
                }
            }
            case "GET_INPUT_TEXT" -> {
                if (mode == MODE_BACK) {
                    host.dispose();
                } else {
                    invokeOrDispose(script, host, "level" + ctx.nextLevel, host.getText());
                }
            }
            case "SEND_ACCEPT_DECLINE" -> invokeOrDispose(script, host,
                    mode == MODE_BACK ? "level" + ctx.lastLevel : "level" + ctx.nextLevel);
            case "SEND_YES_NO" -> invokeOrDispose(script, host,
                    mode == MODE_BACK ? "level" + ctx.lastLevel : "level" + ctx.nextLevel);
            default -> {    // SEND_NEXT / SEND_LAST / SEND_LAST_NEXT / SEND_OK
                if (mode == MODE_BACK) {
                    invokeOrDispose(script, host, "level" + ctx.lastLevel);
                } else {
                    invokeOrDispose(script, host, "level" + ctx.nextLevel);
                }
            }
        }
    }

    private void invokeOrDispose(ConversationScript script, NpcConversationHost host, String fn, Object... args) {
        if (!script.hasFunction(fn)) {
            // 目标函数不存在：兜底到 level / levelnull / leveldispose / dispose
            if (script.hasFunction("levelnull")) {
                script.invoke("levelnull");
            } else {
                host.dispose();
            }
            return;
        }
        try {
            script.invoke(fn, args);
        } catch (RuntimeException e) {
            log.error("nextlevel 函数 {} 失败", fn, e);
            host.dispose();
        }
    }
}
