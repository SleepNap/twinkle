package org.gms.replaceable;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.party.PartyMember;
import org.gms.domain.game.party.PartyState;
import org.gms.domain.game.skill.BuffDefinition;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 同频道组队权限及 Buff 生命周期回归。 */
public class PartyAndBuffTest {
    private static PartyMember member(long id, long session) { return new PartyMember(id, session, "P" + id, 100, 10, 100); }

    @Test public void joiningRequiresLiveInvitationForExactSession() {
        var state = new PartyState(); var system = new PartySystem();
        var party = system.create(state, member(1, 11));
        assertThat(system.join(state, member(2, 22), party.id(), 1000)).isNull();
        assertThat(system.invite(state, 1, member(2, 22), 1000)).isTrue();
        assertThat(system.join(state, member(2, 23), party.id(), 1001)).isNull();
        assertThat(system.join(state, member(2, 22), party.id(), 61_000)).isNull();
        assertThat(system.join(state, member(2, 22), party.id(), 1001)).isNotNull();
        assertThat(system.join(state, member(2, 22), party.id(), 1002)).isNull();
        assertThat(state.of(2).members()).hasSize(2);
    }

    @Test public void fullPartyRejectsJoinAndOnlyLeaderCanExpelOrTransferLeadership() {
        var state = new PartyState(); var system = new PartySystem();
        var party = system.create(state, member(1, 1));
        for (int id = 2; id <= 7; id++) assertThat(system.invite(state, 1, member(id, id), 1000)).isTrue();
        for (int id = 2; id <= 6; id++) assertThat(system.join(state, member(id, id), party.id(), 1001)).isNotNull();
        assertThat(system.join(state, member(7, 7), party.id(), 1001)).isNull();
        assertThat(system.leave(state, 2, 3)).isNull();
        assertThat(system.changeLeader(state, 2, 3)).isNull();
        assertThat(system.changeLeader(state, 1, 7)).isNull();
        assertThat(system.changeLeader(state, 1, 2)).isNotNull();
        assertThat(system.leave(state, 2, 3)).isNotNull();
        assertThat(state.of(3)).isNull();
        assertThat(system.leave(state, 2, 2)).isNotNull();
        assertThat(state.snapshot()).isEmpty();
        assertThat(state.invitation(7)).isNull();
    }

    @Test public void cannotJoinTwoPartiesOrReuseInviteAfterDisband() {
        var state = new PartyState(); var system = new PartySystem();
        var first = system.create(state, member(1, 1));
        var second = system.create(state, member(2, 2));
        system.invite(state, 1, member(3, 3), 1000);
        system.invite(state, 2, member(3, 3), 1000);
        assertThat(system.join(state, member(3, 3), first.id(), 1001)).isNull();
        assertThat(system.join(state, member(3, 3), second.id(), 1001)).isNotNull();
        assertThat(system.create(state, member(3, 3))).isNull();
        system.leave(state, 3, 3);
        assertThat(system.join(state, member(3, 3), second.id(), 1002)).isNull();
    }

    @Test public void buffValidatesLearnedLevelCostsCooldownAndNeverChangesBaseAttributes() {
        var gate = new DefaultVersionGate(); var system = new ProgressionSystem(gate);
        var character = new PlayerCharacter(gate.currentVersion());
        character.setHp(50); character.setMp(7);
        character.putSkill(new SkillEntry(1001003, 1, 0, -1));
        var effect = new BuffDefinition(1001003, 1, 0, 8, 1000, 2000, Map.of(1L << 33, 2));
        assertThat(system.castBuff(character, effect, 1000)).isFalse();
        character.setMp(30);
        int base = character.getStrStat();
        assertThat(system.castBuff(character, effect, 1000)).isTrue();
        assertThat(character.getMp()).isEqualTo(22);
        assertThat(system.castBuff(character, effect, 1001)).isFalse();
        assertThat(system.cancelBuff(character, 0, 1999, true)).isZero();
        assertThat(system.cancelBuff(character, 0, 2000, true)).isEqualTo(1L << 33);
        assertThat(character.buffs()).isEmpty();
        assertThat(character.getStrStat()).isEqualTo((short) base);
        gate.onReload();
        assertThat(system.castBuff(character, effect, 4000)).isFalse();
    }

    @Test public void cancellingReplacedBuffDoesNotCancelItsSuccessor() {
        var gate = new DefaultVersionGate(); var system = new ProgressionSystem(gate);
        var character = new PlayerCharacter(gate.currentVersion());
        character.setHp(50); character.setMp(50);
        character.putSkill(new SkillEntry(1001003, 1, 0, -1));
        character.putSkill(new SkillEntry(2001003, 1, 0, -1));
        var first = new BuffDefinition(1001003, 1, 0, 8, 1000, 0, Map.of(1L << 33, 2));
        var second = new BuffDefinition(2001003, 1, 0, 8, 2000, 0, Map.of(1L << 33, 3));
        assertThat(system.castBuff(character, first, 1000)).isTrue();
        assertThat(system.castBuff(character, second, 1100)).isTrue();
        assertThat(system.cancelBuff(character, 1001003, 1200, false)).isZero();
        assertThat(system.cancelBuff(character, 0, 2001, true)).isZero();
        character.setHp(0);
        assertThat(system.cancelBuff(character, 0, 2100, true)).isEqualTo(1L << 33);
        assertThat(character.buffs()).isEmpty();
    }
}
