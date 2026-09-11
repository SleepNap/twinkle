package org.gms.domain.game.logic;

import org.gms.domain.game.party.PartyMember;
import org.gms.domain.game.party.PartyState;
import org.gms.domain.game.party.PartyState.Party;

/** PartySystem 的稳定调用契约；实现由逻辑包提供。 */
public interface PartySystem {
    public Party create(PartyState state, PartyMember owner);

    public boolean invite(PartyState state, long leader, PartyMember target, long now);

    public Party join(PartyState state, PartyMember member, int partyId, long now);

    public Party leave(PartyState state, long actor, long target);

    public Party changeLeader(PartyState state, long actor, long target);

    public Party update(PartyState state, PartyMember member);
}
