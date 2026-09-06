package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.party.PartyMember;
import org.gms.domain.game.party.PartyState;
import org.gms.domain.game.party.PartyState.Party;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.PartySystem;
import org.gms.domain.game.spi.CharacterState;

import java.time.Clock;
import java.util.function.Predicate;

/** 同频道临时组队入口。目录为频道属主状态，通过会话注册表投递，禁止保存玩家对象。 */
public final class PartyHandler implements PacketHandler, AutoCloseable {
    private final PartyState state = new PartyState();
    private final PartySystem system = new PartySystem();
    private final PlayerSessionRegistry sessions;
    private final int channel;
    private final Clock clock;
    private final Predicate<CharacterState> accepts;

    public PartyHandler(PlayerSessionRegistry sessions, int channel, Clock clock, Predicate<CharacterState> accepts) {
        this.sessions = sessions; this.channel = channel; this.clock = clock; this.accepts = accepts;
    }
    @Override public void handle(PacketSession session, InPacket packet) {
        boolean accepted = false;
        try {
            PlayerCharacter character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 1
                    || sessions.get(character.getId()) != session || !accepts.test(character)) return;
            int action = packet.readByte();
            synchronized (state) {
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
            }
        } finally {
            if (!accepted) session.send(PartyPackets.action(11));
            session.send(GameplayPackets.enableActions());
        }
    }
    public void deny(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character != null) synchronized (state) { state.cancelInvitation(character.getId()); }
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
            PlayerCharacter character = GameplaySession.character(session);
            if (character == null) continue;
            Party current = state.of(member.id());
            int partyId = current == null ? 0 : current.id();
            if (character.getParty() != partyId) { character.setParty(partyId); character.markDirty(); }
            session.send(packet);
        }
    }
    /** 在公共 tick 清理离线/换频道成员并刷新等级与地图，邀请到期自动失效。 */
    public void refresh() {
        synchronized (state) {
            state.expireInvitations(clock.millis());
            for (Party party : state.snapshot().values()) {
                for (PartyMember member : party.members().values()) {
                    PacketSession session = sessions.get(member.id());
                    if (session == null || session.sessionId() != member.sessionId() || GameplaySession.character(session) == null) {
                        leave(member.id(), member.id());
                    } else {
                        Party changed = system.update(state, member(session));
                        if (changed != null) publish(changed, PartyPackets.members(changed, channel, null));
                    }
                }
            }
        }
    }
    private static PartyMember member(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        return new PartyMember(character.getId(), session.sessionId(), character.getName(),
                character.getJob(), character.getLevel(), character.getMap());
    }
    @Override public void close() {
        synchronized (state) {
            for (Party party : state.snapshot().values()) leave(party.leaderId(), party.leaderId());
            state.clear();
        }
    }
}
