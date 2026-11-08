package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 好友申请、接受、删除与整表加载的协议和持久化回归。 */
public class BuddyGameplayTest {
    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final BuddyFixtures.Repository repository = new BuddyFixtures.Repository();
    private final BuddyFixtures.Characters characters = new BuddyFixtures.Characters();
    private final BuddyHandler handler = new BuddyHandler(1, null, null, sessions, repository, characters);

    @Test public void addRequestPersistsPendingBothWaysAndNotifiesOnlineTarget() {
        var owner = joined(1);
        var target = joined(2);

        handler.handle(owner, addRequest("Player2", "Default Group"));

        assertThat(repository.findByOwner(1)).hasSize(1);
        assertThat(repository.findByOwner(1).getFirst().getBuddyId()).isEqualTo(2);
        assertThat(repository.findByOwner(1).getFirst().getStatus()).isEqualTo(BuddyListEntity.PENDING);
        assertThat(repository.findByOwner(2)).hasSize(1);
        assertThat(repository.findByOwner(2).getFirst().getBuddyId()).isEqualTo(1);

        var ownList = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        assertThat(ownList.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(ownList.readByte()).isEqualTo((byte) 7);
        assertThat(ownList.readByte()).isZero();

        InPacket notification = new ByteArrayInPacket(target.sent.getFirst().getBytes());
        assertThat(notification.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(notification.readByte()).isEqualTo((byte) 9);
        assertThat(notification.readInt()).isOne();
        assertThat(notification.readString()).isEqualTo("Player1");
        assertThat(notification.readInt()).isOne();
        assertThat(readFixed(notification, 11)).isEqualTo("Player1");
        assertThat(notification.readByte()).isEqualTo((byte) 0x09);
        assertThat(notification.readByte()).isEqualTo((byte) 0xF0);
        assertThat(notification.readByte()).isEqualTo((byte) 0x01);
        assertThat(notification.readInt()).isEqualTo(0x0F);
        assertThat(readFixed(notification, 13)).isEqualTo("Default Group");
        assertThat(notification.readByte()).isZero();
        assertThat(notification.readInt()).isEqualTo(2);
        assertThat(notification.available()).isZero();
    }

    @Test public void loginLoadsOnlyAcceptedBuddiesWithOnlineChannelAndOfflineName() {
        var owner = joined(1);
        joined(2);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.ACCEPTED));
        repository.insertIfAbsent(row(1, 3, BuddyListEntity.ACCEPTED));
        repository.insertIfAbsent(row(1, 4, BuddyListEntity.PENDING));
        characters.records.put(3L, character(3, "Offline"));

        handler.initialize(owner);

        var packet = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        assertThat(packet.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(packet.readByte()).isEqualTo((byte) 7);
        assertThat(packet.readByte()).isEqualTo((byte) 2);

        assertThat(packet.readInt()).isEqualTo(2);
        assertThat(readFixed(packet, 13)).isEqualTo("Player2");
        assertThat(packet.readByte()).isZero();
        assertThat(packet.readInt()).isZero();
        assertThat(readFixed(packet, 13)).isEqualTo("Default Group");
        assertThat(packet.readInt()).isZero();

        assertThat(packet.readInt()).isEqualTo(3);
        assertThat(readFixed(packet, 13)).isEqualTo("Offline");
        assertThat(packet.readByte()).isZero();
        assertThat(packet.readInt()).isEqualTo(-2);
        assertThat(readFixed(packet, 13)).isEqualTo("Default Group");
        assertThat(packet.readInt()).isZero();

        assertThat(packet.readInt()).isZero();
        assertThat(packet.readInt()).isZero();
        assertThat(packet.available()).isZero();
    }

    @Test public void acceptMarksBothDirectionsAndRefreshesBothLists() {
        var owner = joined(1);
        var target = joined(2);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.PENDING));
        repository.insertIfAbsent(row(2, 1, BuddyListEntity.PENDING));

        handler.handle(owner, idRequest(2, 2));

        assertThat(repository.findByOwner(1).getFirst().getStatus()).isEqualTo(BuddyListEntity.ACCEPTED);
        assertThat(repository.findByOwner(2).getFirst().getStatus()).isEqualTo(BuddyListEntity.ACCEPTED);
        assertThat(listCount(owner)).isOne();
        assertThat(listCount(target)).isOne();
    }

    @Test public void deleteRemovesBothDirectionsAndRefreshesBothLists() {
        var owner = joined(1);
        var target = joined(2);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.ACCEPTED));
        repository.insertIfAbsent(row(2, 1, BuddyListEntity.ACCEPTED));

        handler.handle(owner, idRequest(3, 2));

        assertThat(repository.findByOwner(1)).isEmpty();
        assertThat(repository.findByOwner(2)).isEmpty();
        assertThat(listCount(owner)).isZero();
        assertThat(listCount(target)).isZero();
    }

    @Test public void unknownTargetLeavesRelationshipUntouched() {
        var owner = joined(1);

        handler.handle(owner, addRequest("Ghost", "Default Group"));

        assertThat(repository.findByOwner(1)).isEmpty();
        assertThat(listCount(owner)).isZero();
    }

    @Test public void addRejectsWhenOwnerBuddyListIsFull() {
        var owner = joined(1);
        var target = joined(3);
        owner.character.setBuddyCapacity(1);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.ACCEPTED));
        characters.records.put(2L, character(2, "Player2"));

        handler.handle(owner, addRequest("Player3", "Default Group"));

        assertThat(repository.findByOwner(1)).hasSize(1);
        assertThat(repository.findByOwner(3)).isEmpty();
        assertThat(listCount(owner)).isOne();
        assertThat(target.sent).isEmpty();
    }

    @Test public void loginRefreshesOnlineBuddiesLists() {
        var owner = joined(1);
        var buddy = joined(2);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.ACCEPTED));
        repository.insertIfAbsent(row(2, 1, BuddyListEntity.ACCEPTED));

        handler.initialize(buddy);

        assertThat(buddyListEntryIds(owner)).containsExactly(2L);
        assertThat(buddyListChannel(owner, 2)).isZero();
        assertThat(buddyListEntryIds(buddy)).containsExactly(1L);
    }

    @Test public void offlineRefreshesOnlineBuddiesLists() {
        var owner = joined(1);
        var buddy = joined(2);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.ACCEPTED));
        repository.insertIfAbsent(row(2, 1, BuddyListEntity.ACCEPTED));
        characters.records.put(2L, character(2, "Player2"));
        assertThat(sessions.unregister(2, buddy)).isTrue();

        handler.offline(buddy);

        assertThat(buddyListEntryIds(owner)).containsExactly(2L);
        assertThat(buddyListChannel(owner, 2)).isEqualTo(-2);
    }

    /** 读取整表包中的好友 id 列表。 */
    private static List<Long> buddyListEntryIds(GameplayTestSession session) {
        var packet = new ByteArrayInPacket(session.sent.getLast().getBytes());
        packet.readUnsignedShort();
        assertThat(packet.readByte()).isEqualTo((byte) 7);
        int count = packet.readByte();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add((long) packet.readInt());
            packet.skip(13);
            packet.readByte();
            packet.readInt();
            packet.skip(13);
            packet.readInt();
        }
        return ids;
    }

    private static int buddyListChannel(GameplayTestSession session, long characterId) {
        var packet = new ByteArrayInPacket(session.sent.getLast().getBytes());
        packet.readUnsignedShort();
        packet.readByte();
        int count = packet.readByte();
        for (int i = 0; i < count; i++) {
            int id = packet.readInt();
            packet.skip(13);
            packet.readByte();
            int channel = packet.readInt();
            packet.skip(13);
            packet.readInt();
            if (id == characterId) return channel;
        }
        throw new AssertionError("好友列表中未找到 " + characterId);
    }

    private GameplayTestSession joined(long id) {
        var session = new GameplayTestSession(id, new MapleMap());
        sessions.claim(id, session);
        return session;
    }

    private static int listCount(GameplayTestSession session) {
        OutPacket packet = session.sent.getLast();
        var wire = new ByteArrayInPacket(packet.getBytes());
        wire.readUnsignedShort();
        assertThat(wire.readByte()).isEqualTo((byte) 7);
        return wire.readByte();
    }

    private static ByteArrayInPacket addRequest(String name, String group) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(1).writeString(name).writeString(group);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static ByteArrayInPacket idRequest(int mode, int id) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(mode).writeInt(id);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static String readFixed(InPacket packet, int length) {
        return new String(packet.readBytes(length), InPacket.DEFAULT_CHARSET).trim();
    }

    private static BuddyListEntity row(long owner, long buddy, String status) {
        return BuddyFixtures.row(owner, buddy, status);
    }

    private static PlayerCharacterRecord character(long id, String name) {
        return BuddyFixtures.character(id, name);
    }
}
