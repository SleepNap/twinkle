package org.gms.domain.game.party;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/** 一个频道独占的临时组队状态；跨频道转移时退出，由目标频道重新建立会话。 */
public final class PartyState {
    public record Party(int id, long leaderId, Map<Long, PartyMember> members) {
        public Party { members = Collections.unmodifiableMap(new LinkedHashMap<>(members)); }
    }
    public record Invitation(int partyId, long targetSessionId, long expiresAt) { }
    private int sequence;
    private final Map<Integer, Party> parties = new LinkedHashMap<>();
    private final Map<Long, Integer> membership = new LinkedHashMap<>();
    private final Map<Long, Invitation> invitations = new LinkedHashMap<>();

    public int nextId() { sequence = Math.incrementExact(sequence); return sequence; }
    public Party party(int id) { return parties.get(id); }
    public Party of(long member) { return parties.get(membership.get(member)); }
    public Invitation invitation(long target) { return invitations.get(target); }
    public void invite(long target, Invitation invitation) { invitations.put(target, invitation); }
    public void cancelInvitation(long target) { invitations.remove(target); }
    public void expireInvitations(long now) { invitations.values().removeIf(invite -> invite.expiresAt() <= now); }
    public Map<Integer, Party> snapshot() { return Map.copyOf(parties); }
    public void replace(Party party) {
        Party old = parties.put(party.id(), party);
        if (old != null) old.members().keySet().forEach(membership::remove);
        party.members().keySet().forEach(member -> membership.put(member, party.id()));
    }
    public void remove(int id) {
        Party old = parties.remove(id);
        if (old != null) old.members().keySet().forEach(membership::remove);
        invitations.values().removeIf(invitation -> invitation.partyId() == id);
    }
    public void clear() { parties.clear(); membership.clear(); invitations.clear(); }
}
