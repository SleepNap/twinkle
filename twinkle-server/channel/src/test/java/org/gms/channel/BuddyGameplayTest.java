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
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 好友申请、接受、删除与整表加载的协议和持久化回归。 */
public class BuddyGameplayTest {
    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final BuddyRepository repository = new BuddyRepository();
    private final Characters characters = new Characters();
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
        BuddyListEntity entity = new BuddyListEntity();
        entity.setOwnerId(owner);
        entity.setBuddyId(buddy);
        entity.setStatus(status);
        return entity;
    }

    private static PlayerCharacterRecord character(long id, String name) {
        PlayerCharacterRecord record = new PlayerCharacterRecord();
        record.setId(id);
        record.setName(name);
        return record;
    }

    private static final class BuddyRepository implements BuddyListRepository {
        private final Map<Long, List<BuddyListEntity>> rows = new HashMap<>();

        @Override public List<BuddyListEntity> findByOwner(long ownerId) {
            return new ArrayList<>(rows.getOrDefault(ownerId, List.of()));
        }

        @Override public boolean exists(long ownerId, long buddyId) {
            return findByOwner(ownerId).stream().anyMatch(row -> row.getBuddyId() == buddyId);
        }

        @Override public boolean insertIfAbsent(BuddyListEntity buddy) {
            if (exists(buddy.getOwnerId(), buddy.getBuddyId())) return false;
            rows.computeIfAbsent(buddy.getOwnerId(), ignored -> new ArrayList<>()).add(buddy);
            return true;
        }

        @Override public void updateStatus(long ownerId, long buddyId, String status) {
            for (BuddyListEntity row : rows.getOrDefault(ownerId, List.of())) {
                if (row.getBuddyId() == buddyId) row.setStatus(status);
            }
        }

        @Override public void delete(long ownerId, long buddyId) {
            rows.getOrDefault(ownerId, new ArrayList<>()).removeIf(row -> row.getBuddyId() == buddyId);
        }
    }

    private static final class Characters implements PlayerCharacterRepository {
        private final Map<Long, PlayerCharacterRecord> records = new HashMap<>();

        @Override public List<PlayerCharacterRecord> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override public Optional<PlayerCharacterRecord> findById(long id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override public Optional<PlayerCharacterRecord> findByName(String name) {
            return records.values().stream().filter(record -> record.getName().equals(name)).findFirst();
        }

        @Override public boolean existsByName(String name) {
            return findByName(name).isPresent();
        }

        @Override public void insert(PlayerCharacterRecord chr) { }

        @Override public void save(PlayerCharacterRecord chr) { }
    }
}
