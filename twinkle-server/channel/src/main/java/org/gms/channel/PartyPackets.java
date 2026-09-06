package org.gms.channel;

import org.gms.domain.game.party.PartyMember;
import org.gms.domain.game.party.PartyState.Party;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.v83.V83ChannelId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v83 队伍消息独立编码；字段规格通过北斗收发包行为核对。 */
public final class PartyPackets {
    private PartyPackets() { }
    public static ByteArrayOutPacket action(int action) {
        var packet = GameplayPackets.packet(SendOpcode.PARTY_OPERATION);
        packet.writeByte(action);
        return packet;
    }
    public static OutPacket created(Party party) {
        var packet = action(8);
        packet.writeInt(party.id());
        door(packet);
        return packet;
    }
    public static OutPacket invite(Party party, String leader) {
        var packet = action(4);
        packet.writeInt(party.id()); packet.writeString(leader); packet.writeByte(0);
        return packet;
    }
    public static OutPacket members(Party party, int channel, String joinedName) {
        var packet = action(joinedName == null ? 7 : 15);
        packet.writeInt(party.id());
        if (joinedName != null) packet.writeString(joinedName);
        roster(packet, party, channel);
        return packet;
    }
    public static OutPacket left(Party before, Party after, PartyMember member, boolean expelled, int channel) {
        var packet = action(12);
        packet.writeInt(before.id()); packet.writeInt((int) member.id()); packet.writeBool(after != null);
        if (after == null) packet.writeInt(before.id());
        else {
            packet.writeBool(expelled); packet.writeString(member.name()); roster(packet, after, channel);
        }
        return packet;
    }
    public static OutPacket leader(long id) {
        var packet = action(27); packet.writeInt((int) id); packet.writeByte(0); return packet;
    }
    private static void roster(OutPacket packet, Party party, int channel) {
        List<PartyMember> members = new ArrayList<>(party.members().values());
        while (members.size() < 6) members.add(new PartyMember(0, 0, "", 0, 0, 0));
        members.forEach(member -> packet.writeInt((int) member.id()));
        members.forEach(member -> packet.writeBytes(Arrays.copyOf(member.name().getBytes(InPacket.DEFAULT_CHARSET), 13)));
        members.forEach(member -> packet.writeInt(member.job()));
        members.forEach(member -> packet.writeInt(member.level()));
        members.forEach(member -> packet.writeInt(member.id() == 0 ? -2 : V83ChannelId.toWire(channel)));
        packet.writeInt((int) party.leaderId());
        members.forEach(member -> packet.writeInt(member.mapId()));
        members.forEach(member -> door(packet));
    }
    private static void door(OutPacket packet) {
        packet.writeInt(999_999_999); packet.writeInt(999_999_999); packet.writeInt(0); packet.writeInt(0);
    }
}
