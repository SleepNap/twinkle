package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
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

    @Test public void acceptDeliversNextPendingRequest() {
        var owner = joined(1);
        // 第一段：Player2 的申请，确认后应继续推 Player3 的申请。
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.PENDING));
        repository.insertIfAbsent(row(2, 1, BuddyListEntity.PENDING));
        repository.insertIfAbsent(row(1, 3, BuddyListEntity.PENDING));
        repository.insertIfAbsent(BuddyFixtures.row(3, 1, BuddyListEntity.PENDING, "Family"));
        characters.records.put(2L, character(2, "Player2"));
        characters.records.put(3L, character(3, "Player3"));

        handler.handle(owner, idRequest(2, 2));

        assertThat(owner.sent).hasSize(2);
        var ownList = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        assertThat(ownList.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(ownList.readByte()).isEqualTo((byte) 7);
        assertThat(ownList.readByte()).isOne();

        var next = new ByteArrayInPacket(owner.sent.getLast().getBytes());
        assertThat(next.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(next.readByte()).isEqualTo((byte) 9);
        assertThat(next.readInt()).isEqualTo(3);
        assertThat(next.readString()).isEqualTo("Player3");
        assertThat(next.readInt()).isEqualTo(3);
        assertThat(readFixed(next, 11)).isEqualTo("Player3");
        next.skip(3);
        assertThat(next.readInt()).isEqualTo(0x0F);
        assertThat(readFixed(next, 13)).isEqualTo("Family");
        next.readByte();
        assertThat(next.readInt()).isOne();
        assertThat(next.available()).isZero();
    }

    @Test public void acceptRejectsWhenOwnerListIsFull() {
        var owner = joined(1);
        owner.character.setBuddyCapacity(1);
        repository.insertIfAbsent(row(1, 2, BuddyListEntity.PENDING));
        repository.insertIfAbsent(row(2, 1, BuddyListEntity.PENDING));
        repository.insertIfAbsent(row(1, 5, BuddyListEntity.ACCEPTED));
        repository.insertIfAbsent(row(5, 1, BuddyListEntity.ACCEPTED));

        handler.handle(owner, idRequest(2, 2));

        assertThat(repository.findByOwner(1).stream()
                .filter(relation -> relation.getBuddyId() == 2).findFirst().orElseThrow().getStatus())
                .isEqualTo(BuddyListEntity.PENDING);
        assertThat(repository.findByOwner(2).getFirst().getStatus()).isEqualTo(BuddyListEntity.PENDING);
        assertThat(owner.sent).hasSize(1);
        var notice = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        assertThat(notice.readUnsignedShort()).isEqualTo(SendOpcode.SERVERMESSAGE.getValue());
        assertThat(notice.readByte()).isOne();
        assertThat(notice.readString()).isEqualTo(I18n.message("game.buddy.self_full"));
        assertThat(notice.available()).isZero();
    }

    @Test public void acceptIgnoresRelationThatIsNotPending() {
        var owner = joined(1);

        handler.handle(owner, idRequest(2, 2));

        assertThat(owner.sent).isEmpty();
        assertThat(repository.findByOwner(1)).isEmpty();
        assertThat(repository.findByOwner(2)).isEmpty();
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

    @Test public void addStoresSelectedGroupAndFullListShowsIt() {
        var owner = joined(1);
        var target = joined(2);

        handler.handle(owner, addRequest("Player2", "Friends"));

        assertThat(repository.findByOwner(1).getFirst().getGroupName()).isEqualTo("Friends");
        assertThat(repository.findByOwner(2).getFirst().getGroupName())
                .isEqualTo(BuddyListEntity.DEFAULT_GROUP);

        handler.handle(target, idRequest(2, 1));
        owner.sent.clear();
        handler.initialize(owner);

        var packet = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        packet.readUnsignedShort();
        assertThat(packet.readByte()).isEqualTo((byte) 7);
        assertThat(packet.readByte()).isEqualTo((byte) 1);
        assertThat(packet.readInt()).isEqualTo(2);
        assertThat(readFixed(packet, 13)).isEqualTo("Player2");
        packet.readByte();
        packet.readInt();
        assertThat(readFixed(packet, 13)).isEqualTo("Friends");
        packet.readInt();
        assertThat(packet.readInt()).isZero();
        assertThat(packet.available()).isZero();
    }

    @Test public void loginReplaysPendingRequestFromOtherPlayer() {
        var owner = joined(1);
        repository.insertIfAbsent(BuddyFixtures.row(2, 1, BuddyListEntity.PENDING, "Friends"));
        characters.records.put(2L, character(2, "Player2"));

        handler.initialize(owner);

        assertThat(owner.sent).hasSize(2);
        var request = new ByteArrayInPacket(owner.sent.getLast().getBytes());
        assertThat(request.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(request.readByte()).isEqualTo((byte) 9);
        assertThat(request.readInt()).isEqualTo(2);
        assertThat(request.readString()).isEqualTo("Player2");
        assertThat(request.readInt()).isEqualTo(2);
        assertThat(readFixed(request, 11)).isEqualTo("Player2");
        request.skip(3);
        assertThat(request.readInt()).isEqualTo(0x0F);
        assertThat(readFixed(request, 13)).isEqualTo("Friends");
        request.readByte();
        assertThat(request.readInt()).isOne();
        assertThat(request.available()).isZero();
    }

    @Test public void loginReplaysOnlyFirstPendingRequest() {
        var owner = joined(1);
        repository.insertIfAbsent(BuddyFixtures.row(2, 1, BuddyListEntity.PENDING, "Friends"));
        repository.insertIfAbsent(BuddyFixtures.row(3, 1, BuddyListEntity.PENDING, "Family"));
        characters.records.put(2L, character(2, "Player2"));
        characters.records.put(3L, character(3, "Player3"));

        handler.initialize(owner);

        // 整表 + 一个申请：客户端一次只显示一个弹窗，其余申请在确认后逐个补推。
        assertThat(owner.sent).hasSize(2);
        var list = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        assertThat(list.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(list.readByte()).isEqualTo((byte) 7);
        var request = new ByteArrayInPacket(owner.sent.getLast().getBytes());
        assertThat(request.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(request.readByte()).isEqualTo((byte) 9);
        assertThat(request.readInt()).isIn(2, 3);
    }

    @Test public void loginDoesNotReplayOwnOutgoingPendingAsRequest() {
        var owner = joined(1);
        repository.insertIfAbsent(BuddyFixtures.row(1, 2, BuddyListEntity.PENDING, "Friends"));
        characters.records.put(2L, character(2, "Player2"));

        handler.initialize(owner);

        assertThat(owner.sent).hasSize(1);
        assertThat(listCount(owner)).isZero();
    }

    @Test public void groupBeyondWireWidthFallsBackToDefault() {
        var owner = joined(1);
        joined(2);
        joined(3);

        handler.handle(owner, addRequest("Player2", "ABCDEFGHIJKLMNO"));
        handler.handle(owner, addRequest("Player3", "好友分组测试组"));

        assertThat(repository.findByOwner(1)).hasSize(2);
        assertThat(repository.findByOwner(1).getFirst().getGroupName())
                .isEqualTo(BuddyListEntity.DEFAULT_GROUP);
        assertThat(repository.findByOwner(1).getLast().getGroupName())
                .isEqualTo(BuddyListEntity.DEFAULT_GROUP);
    }

    @Test public void addRejectsWhenTargetBuddyListIsFull() {
        var owner = joined(1);
        var target = joined(2);
        target.character.setBuddyCapacity(1);
        repository.insertIfAbsent(BuddyFixtures.row(2, 3, BuddyListEntity.ACCEPTED));
        characters.records.put(3L, character(3, "Player3"));

        handler.handle(owner, addRequest("Player2", "Default Group"));

        assertThat(repository.findByOwner(1)).isEmpty();
        assertThat(repository.findByOwner(2)).hasSize(1);
        var notice = new ByteArrayInPacket(owner.sent.getLast().getBytes());
        assertThat(notice.readUnsignedShort()).isEqualTo(SendOpcode.SERVERMESSAGE.getValue());
        assertThat(notice.readByte()).isEqualTo((byte) 1);
        assertThat(notice.readString()).isEqualTo(I18n.message("game.buddy.target_full", "Player2"));
        assertThat(notice.available()).isZero();
    }

    @Test public void addExistingBuddyChangesGroupWithoutNewRequest() {
        var owner = joined(1);
        var target = joined(2);
        repository.insertIfAbsent(BuddyFixtures.row(1, 2, BuddyListEntity.ACCEPTED, "Default Group"));
        repository.insertIfAbsent(BuddyFixtures.row(2, 1, BuddyListEntity.ACCEPTED, "Default Group"));

        handler.handle(owner, addRequest("Player2", "Friends"));

        assertThat(repository.findByOwner(1).getFirst().getGroupName()).isEqualTo("Friends");
        assertThat(target.sent).isEmpty();
        assertThat(buddyListGroup(owner, 2)).isEqualTo("Friends");
    }

    @Test public void addExistingSameGroupNotifiesWithoutNewRequest() {
        var owner = joined(1);
        var target = joined(2);
        repository.insertIfAbsent(BuddyFixtures.row(1, 2, BuddyListEntity.ACCEPTED, "Friends"));
        repository.insertIfAbsent(BuddyFixtures.row(2, 1, BuddyListEntity.ACCEPTED, "Friends"));

        handler.handle(owner, addRequest("Player2", "Friends"));

        assertThat(repository.findByOwner(1).getFirst().getGroupName()).isEqualTo("Friends");
        assertThat(target.sent).isEmpty();
        var notice = new ByteArrayInPacket(owner.sent.getLast().getBytes());
        assertThat(notice.readUnsignedShort()).isEqualTo(SendOpcode.SERVERMESSAGE.getValue());
        assertThat(notice.readByte()).isEqualTo((byte) 1);
        assertThat(notice.readString()).isEqualTo(I18n.message("game.buddy.already_friend", "Player2"));
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

    private static String buddyListGroup(GameplayTestSession session, long characterId) {
        var packet = new ByteArrayInPacket(session.sent.getLast().getBytes());
        packet.readUnsignedShort();
        packet.readByte();
        int count = packet.readByte();
        for (int i = 0; i < count; i++) {
            int id = packet.readInt();
            packet.skip(13);
            packet.readByte();
            packet.readInt();
            String group = readFixed(packet, 13);
            packet.readInt();
            if (id == characterId) return group;
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
