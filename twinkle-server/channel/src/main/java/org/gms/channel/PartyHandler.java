package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.party.PartyMember;
import org.gms.domain.game.party.PartyState;
import org.gms.domain.game.party.PartyState.Party;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.replaceable.PartySystem;
import org.gms.domain.game.spi.CharacterState;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** 同频道临时组队入口。目录为频道属主状态，通过会话注册表投递，禁止保存玩家对象。 */
public final class PartyHandler implements PacketHandler, AutoCloseable {
    private final PartyState state = new PartyState();
    private final PartySystem system = new PartySystem();
    private final PlayerSessionRegistry sessions;
    private final int channel;
    private final Clock clock;
    private final Predicate<CharacterState> accepts;
    /** 冻结期间只保留最后一份通知所需的队伍身份，恢复后发送完整状态，避免积压封包。 */
    private final Map<PacketSession, Party> deferred = new HashMap<>();

    public PartyHandler(PlayerSessionRegistry sessions, int channel, Clock clock, Predicate<CharacterState> accepts) {
        this.sessions = sessions; this.channel = channel; this.clock = clock; this.accepts = accepts;
    }
    @Override public void handle(PacketSession session, InPacket packet) {
        sessions.coordinate(() -> { handleOwned(session, packet); return null; });
    }

    private void handleOwned(PacketSession session, InPacket packet) {
        boolean accepted = false;
        try {
            PlayerCharacter character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 1
                    || sessions.get(character.getId()) != session || !accepts.test(character)) return;
            int action = packet.readByte();
            long actor = character.getId();
            switch (action) {
                case 1 -> {
                    Party party = system.create(state, member(session));
                    if (party != null) { publish(party, PartyPackets.created(party)); accepted = true; }
                }
                case 2 -> accepted = leave(actor, actor);
                case 3 -> {
                    if (packet.available() < 4) return;
                    Party party = system.join(state, member(session), packet.readInt(), clock.millis());
                    if (party != null) { publish(party, PartyPackets.members(party, channel, character.getName())); accepted = true; }
                }
                case 4 -> {
                    if (packet.available() < 2) return;
                    String name = packet.readString();
                    PacketSession target = sessions.all().stream().filter(candidate -> {
                        PlayerCharacter other = GameplaySession.character(candidate);
                        return other != null && other.getWorld() == character.getWorld() && name.equals(other.getName());
                    }).findFirst().orElse(null);
                    if (target == null || target == session) return;
                    if (state.of(actor) == null) {
                        Party created = system.create(state, member(session));
                        if (created == null) return;
                        publish(created, PartyPackets.created(created));
                    }
                    if (system.invite(state, actor, member(target), clock.millis())) {
                        target.send(PartyPackets.invite(state.of(actor), character.getName())); accepted = true;
                    }
                }
                case 5 -> { if (packet.available() >= 4) accepted = leave(actor, packet.readInt()); }
                case 6 -> {
                    if (packet.available() < 4) return;
                    Party party = system.changeLeader(state, actor, packet.readInt());
                    if (party != null) { publish(party, PartyPackets.leader(party.leaderId())); accepted = true; }
                }
                default -> { }
            }
        } finally {
            if (!accepted) session.send(PartyPackets.action(11));
            session.send(GameplayPackets.enableActions());
        }
    }
    public void deny(PacketSession session) {
        sessions.coordinate(() -> { denyOwned(session); return null; });
    }

    private void denyOwned(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character != null) state.cancelInvitation(character.getId());
    }

    /** MULTI_CHAT 的队伍分支：忽略客户端收件人列表，按服务端队伍及连接代际确定受众。 */
    public void chat(PacketSession session, InPacket packet) {
        sessions.coordinate(() -> { chatOwned(session, packet); return null; });
    }

    private void chatOwned(PacketSession session, InPacket packet) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || sessions.get(character.getId()) != session || packet.available() < 4) return;
        int mode = packet.readByte() & 255, count = packet.readByte() & 255;
        if (mode != 1 || packet.available() < count * 4 + 2) return;
        packet.skip(count * 4);
        int length = packet.readUnsignedShort();
        if (length == 0 || length > 254 || packet.available() != length) return;
        String text = new String(packet.readBytes(length), InPacket.DEFAULT_CHARSET).trim();
        if (text.isEmpty() || text.length() > 127 || text.chars().anyMatch(Character::isISOControl)) return;
        Party party = state.of(character.getId());
        if (party == null || !accepts.test(character)) return;
        PartyMember sender = party.members().get(character.getId());
        if (sender == null || sender.sessionId() != session.sessionId()) return;
        for (PartyMember member : party.members().values()) {
            PacketSession peer = sessions.get(member.id());
            if (peer != null && peer != session && peer.sessionId() == member.sessionId()
                    && GameplaySession.character(peer) != null) {
                peer.send(PlayerUtilityPackets.partyChat(character.getName(), text));
            }
        }
    }
    private boolean leave(long actor, long target) {
        Party before = system.leave(state, actor, target);
        if (before == null) return false;
        Party after = state.party(before.id());
        publish(before, PartyPackets.left(before, after, before.members().get(target), actor != target, channel));
        return true;
    }
    private void publish(Party audience, OutPacket packet) {
        for (PartyMember member : audience.members().values()) {
            PacketSession session = sessions.get(member.id());
            if (session == null || session.sessionId() != member.sessionId()) continue;
            if (session.stage() == SessionStage.IN_GAME && session.getAttr("stateTransfer") != null) {
                deferred.put(session, audience);
                continue;
            }
            PlayerCharacter character = GameplaySession.character(session);
            if (character == null) continue;
            Party current = state.of(member.id());
            int partyId = current == null ? 0 : current.id();
            if (character.getParty() != partyId) { character.setParty(partyId); character.markDirty(); }
            session.send(packet);
        }
    }
    /** 频道 Tick 清理已离线成员；存档期间暂停操作不等于离线，失败恢复后仍保留队伍。 */
    public void refresh() {
        sessions.coordinate(() -> { refreshOwned(); return null; });
    }

    private void refreshOwned() {
        state.expireInvitations(clock.millis());
        for (Party party : state.snapshot().values()) {
            for (PartyMember member : party.members().values()) {
                PacketSession session = sessions.get(member.id());
                if (session == null || session.sessionId() != member.sessionId()
                        || session.stage() != SessionStage.IN_GAME || session.getAttr("character") == null) {
                    leave(member.id(), member.id());
                } else if (session.getAttr("stateTransfer") == null) {
                    Party changed = system.update(state, member(session));
                    if (changed != null) publish(changed, PartyPackets.members(changed, channel, null));
                }
            }
        }
        var updates = deferred.entrySet().iterator();
        while (updates.hasNext()) {
            var update = updates.next();
            PacketSession session = update.getKey();
            PlayerCharacter character = session.getAttr("character");
            if (character == null || sessions.get(character.getId()) != session || session.stage() != SessionStage.IN_GAME) {
                updates.remove();
                continue;
            }
            if (session.getAttr("stateTransfer") != null) continue;
            Party current = state.of(character.getId());
            character.setParty(current == null ? 0 : current.id());
            session.send(current == null
                    ? PartyPackets.left(update.getValue(), null, update.getValue().members().get(character.getId()), false, channel)
                    : PartyPackets.members(current, channel, null));
            updates.remove();
        }
    }
    private static PartyMember member(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        return new PartyMember(character.getId(), session.sessionId(), character.getName(),
                character.getJob(), character.getLevel(), character.getMap());
    }
    @Override public void close() {
        sessions.coordinate(() -> { closeOwned(); return null; });
    }

    private void closeOwned() {
        for (Party party : state.snapshot().values()) leave(party.leaderId(), party.leaderId());
        state.clear();
        deferred.clear();
    }
}
