package org.gms.logic.game;

import org.gms.domain.game.logic.*;

import org.gms.domain.game.party.PartyMember;
import org.gms.domain.game.party.PartyState;
import org.gms.domain.game.party.PartyState.Party;
import org.gms.domain.game.party.PartyState.Invitation;

import java.util.LinkedHashMap;
import java.util.Map;

/** 无跨操作状态的同频道组队规则：会话绑定邀请、六人上限和队长权限均服务端判定。 */
public final class DefaultPartySystem implements PartySystem {
    public Party create(PartyState state, PartyMember owner) {
        synchronized (state) {
            if (state.of(owner.id()) != null || owner.id() <= 0) return null;
            Party party = new Party(state.nextId(), owner.id(), Map.of(owner.id(), owner));
            state.replace(party);
            return party;
        }
    }
    public boolean invite(PartyState state, long leader, PartyMember target, long now) {
        synchronized (state) {
            Party party = state.of(leader);
            if (party == null || party.leaderId() != leader || party.members().size() >= 6
                    || state.of(target.id()) != null) return false;
            state.invite(target.id(), new Invitation(party.id(), target.sessionId(), now + 60_000));
            return true;
        }
    }
    public Party join(PartyState state, PartyMember member, int partyId, long now) {
        synchronized (state) {
            Invitation invite = state.invitation(member.id());
            Party party = state.party(partyId);
            if (invite == null || invite.partyId() != partyId || invite.targetSessionId() != member.sessionId()
                    || invite.expiresAt() <= now || party == null || party.members().size() >= 6
                    || state.of(member.id()) != null) return null;
            Map<Long, PartyMember> members = new LinkedHashMap<>(party.members());
            members.put(member.id(), member);
            Party joined = new Party(party.id(), party.leaderId(), members);
            state.replace(joined);
            state.cancelInvitation(member.id());
            return joined;
        }
    }
    /** 返回操作前队伍，以便向退出者和剩余成员发包；队长退出即解散。 */
    public Party leave(PartyState state, long actor, long target) {
        synchronized (state) {
            Party party = state.of(actor);
            if (party == null || !party.members().containsKey(target)
                    || actor != target && party.leaderId() != actor) return null;
            if (target == party.leaderId()) {
                state.remove(party.id());
            } else {
                Map<Long, PartyMember> members = new LinkedHashMap<>(party.members());
                members.remove(target);
                state.replace(new Party(party.id(), party.leaderId(), members));
            }
            state.cancelInvitation(target);
            return party;
        }
    }
    public Party changeLeader(PartyState state, long actor, long target) {
        synchronized (state) {
            Party party = state.of(actor);
            if (party == null || party.leaderId() != actor || actor == target || !party.members().containsKey(target)) return null;
            Party changed = new Party(party.id(), target, party.members());
            state.replace(changed);
            return changed;
        }
    }
    public Party update(PartyState state, PartyMember member) {
        synchronized (state) {
            Party party = state.of(member.id());
            if (party == null || party.members().get(member.id()).equals(member)) return null;
            Map<Long, PartyMember> members = new LinkedHashMap<>(party.members());
            members.put(member.id(), member);
            Party changed = new Party(party.id(), party.leaderId(), members);
            state.replace(changed);
            return changed;
        }
    }
}
