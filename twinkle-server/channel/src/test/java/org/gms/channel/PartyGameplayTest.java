package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** 组队收发包与离线清理，使用内存连接模拟两名玩家。 */
public class PartyGameplayTest {
    @Test public void createInviteJoinAndDisconnectUpdatesBothClients() {
        var map = new MapleMap();
        var leader = new GameplayTestSession(1, map); var guest = new GameplayTestSession(2, map);
        var sessions = new PlayerSessionRegistry(); sessions.claim(1, leader); sessions.claim(2, guest);
        try (var handler = new PartyHandler(sessions, 1, Clock.systemUTC(), state -> true)) {
            handler.handle(leader, input(1));
            int partyId = leader.character.getParty();
            assertThat(partyId).isPositive();
            ByteArrayOutPacket request = new ByteArrayOutPacket(); request.writeByte(4); request.writeString("Player2");
            handler.handle(leader, new ByteArrayInPacket(request.getBytes()));
            var invitation = new ByteArrayInPacket(guest.sent.getFirst().getBytes());
            assertThat(invitation.readUnsignedShort()).isEqualTo(SendOpcode.PARTY_OPERATION.getValue());
            assertThat(invitation.readByte()).isEqualTo((byte) 4);
            assertThat(invitation.readInt()).isEqualTo(partyId);
            handler.handle(guest, input(3, partyId));
            assertThat(guest.character.getParty()).isEqualTo(partyId);
            var outsider = new GameplayTestSession(3, map); sessions.claim(3, outsider);
            leader.sent.clear(); guest.sent.clear();
            var chat = new ByteArrayOutPacket(); chat.writeByte(1).writeByte(1).writeInt(3).writeString("集合");
            handler.chat(leader, new ByteArrayInPacket(chat.getBytes()));
            assertThat(outsider.sent).isEmpty(); assertThat(leader.sent).isEmpty(); assertThat(guest.sent).hasSize(1);
            var message = new ByteArrayInPacket(guest.sent.getFirst().getBytes());
            assertThat(message.readUnsignedShort()).isEqualTo(SendOpcode.MULTICHAT.getValue());
            assertThat(message.readByte()).isOne(); assertThat(message.readString()).isEqualTo("Player1");
            assertThat(message.readString()).isEqualTo("集合"); assertThat(message.available()).isZero();
            handler.chat(outsider, new ByteArrayInPacket(chat.getBytes())); assertThat(guest.sent).hasSize(1);
            sessions.unregister(1, leader);
            handler.refresh();
            assertThat(guest.character.getParty()).isZero();
            var disband = new ByteArrayInPacket(guest.sent.getLast().getBytes());
            disband.skip(2);
            assertThat(disband.readByte()).isEqualTo((byte) 12);
            assertThat(disband.readInt()).isEqualTo(partyId);
            assertThat(disband.readInt()).isEqualTo(1);
            assertThat(disband.readByte()).isZero();
        }
    }
    private static ByteArrayInPacket input(int action, int... arguments) {
        var packet = new ByteArrayOutPacket(); packet.writeByte(action);
        for (int value : arguments) packet.writeInt(value);
        return new ByteArrayInPacket(packet.getBytes());
    }
}
