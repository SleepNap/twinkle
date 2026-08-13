package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.Character;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.agent.PlayerSupportAgent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 普通聊天与玩家值班 GM 入口。
 *
 * <p>普通文本仍在当前地图广播；以 {@code @gm } 开头的文本不广播，交给异步只读 Agent，
 * 并仅向提问玩家返回结果。外部模型调用不占用 Netty/游戏 tick 线程。
 */
@Log4j2
public final class GeneralChatHandler implements PacketHandler {

    private static final String AGENT_PREFIX = "@gm ";
    private static final int MAX_CHAT_LENGTH = 500;
    private static final int MAX_TRACKED_PLAYERS = 2048;
    private static final int NOTICE_CHUNK_LENGTH = 120;

    private final PlayerSessionRegistry sessions;
    private final PlayerSupportAgent agent;
    private final long cooldownNanos;
    private final Map<Long, Long> lastAgentRequests = new LinkedHashMap<>(64, 0.75F, true);

    public GeneralChatHandler(PlayerSessionRegistry sessions, PlayerSupportAgent agent, int cooldownSeconds) {
        this.sessions = sessions;
        this.agent = agent;
        this.cooldownNanos = TimeUnit.SECONDS.toNanos(Math.max(1, cooldownSeconds));
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.chat.outside_stage"));
            return;
        }
        Character character = session.getAttr("character");
        if (character == null) {
            session.close(I18n.message("error.chat.not_in_map"));
            return;
        }
        if (packet.available() < 2) {
            return;
        }

        String text;
        try {
            text = packet.readString().trim();
        } catch (RuntimeException e) {
            session.close(I18n.message("error.chat.invalid_packet"));
            return;
        }
        int show = packet.available() > 0 ? packet.readByte() & 0xFF : 0;
        if (text.isEmpty() || text.length() > MAX_CHAT_LENGTH) {
            sendNotice(session, I18n.message("game.chat.length_invalid", MAX_CHAT_LENGTH));
            return;
        }

        if (text.regionMatches(true, 0, AGENT_PREFIX, 0, AGENT_PREFIX.length())) {
            handleAgentQuestion(session, character, text.substring(AGENT_PREFIX.length()).trim());
            return;
        }
        if (character.getMapObject() != null) {
            sessions.broadcastToMap(character.getMapObject(),
                    ChannelPacketFactory.chatText(character.getId(), false, text, show));
        }
    }

    private void handleAgentQuestion(PacketSession session, Character character, String question) {
        if (question.isEmpty()) {
            sendNotice(session, I18n.message("game.chat.gm_usage"));
            return;
        }
        if (!agent.available()) {
            sendNotice(session, I18n.message("game.chat.gm_disabled"));
            return;
        }
        long retryAfterSeconds = reserveRequest(character.getId());
        if (retryAfterSeconds > 0) {
            sendNotice(session, I18n.message("game.chat.gm_rate_limited", retryAfterSeconds));
            return;
        }

        long expectedSessionId = session.sessionId();
        sendNotice(session, I18n.message("game.chat.gm_accepted"));
        PlayerSupportAgent.PlayerQuestion request = new PlayerSupportAgent.PlayerQuestion(
                character.getId(), character.getName(), expectedSessionId, question);
        agent.ask(request).whenComplete((reply, error) -> {
            if (sessions.get(character.getId()) != session
                    || session.sessionId() != expectedSessionId
                    || session.stage() != SessionStage.IN_GAME) {
                return;
            }
            if (error != null || reply == null || reply.text() == null || reply.text().isBlank()) {
                log.warn(I18n.message("log.chat.gm_call_failed"),
                        character.getId(), expectedSessionId, error);
                sendNotice(session, I18n.message("game.chat.gm_error"));
                return;
            }
            sendChunkedNotice(session, reply.text());
            if (!reply.auditRefs().isEmpty()) {
                sendNotice(session, I18n.message("game.chat.gm_audit_refs", String.join(",", reply.auditRefs())));
            }
        });
    }

    /** 预留本次请求；返回 0 表示成功，否则为建议等待秒数。 */
    private long reserveRequest(long characterId) {
        long now = System.nanoTime();
        synchronized (lastAgentRequests) {
            Long previous = lastAgentRequests.get(characterId);
            if (previous != null && now - previous < cooldownNanos) {
                long remaining = cooldownNanos - (now - previous);
                return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining) + 1);
            }
            lastAgentRequests.put(characterId, now);
            while (lastAgentRequests.size() > MAX_TRACKED_PLAYERS) {
                Long oldest = lastAgentRequests.keySet().iterator().next();
                lastAgentRequests.remove(oldest);
            }
            return 0;
        }
    }

    private static void sendChunkedNotice(PacketSession session, String text) {
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        for (int start = 0; start < normalized.length(); start += NOTICE_CHUNK_LENGTH) {
            int end = Math.min(normalized.length(), start + NOTICE_CHUNK_LENGTH);
            sendNotice(session, I18n.message("game.chat.gm_reply_prefix", normalized.substring(start, end)));
        }
    }

    private static void sendNotice(PacketSession session, String text) {
        session.send(ChannelPacketFactory.serverNotice(text));
    }
}
