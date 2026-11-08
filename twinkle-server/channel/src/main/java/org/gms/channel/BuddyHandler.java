package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.domain.game.PlayerCharacter;
import org.gms.event.EventBus;
import org.gms.i18n.I18n;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.service.intercoord.IntercoordService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 好友列表（RecvOpcode.BUDDYLIST_MODIFY 0x82）。
 *
 * <p>v83 收包：mode(1B) + 参数。mode 1 加好友读目标名/分组；mode 2 接受和 mode 3
 * 删除读目标角色 id。持久化仍以 `buddy_list` 表为唯一真值，读写经后台 IO 队列；
 * 完成回包通过版本门回到原会话，断线重登后的新会话不会收到旧结果。
 *
 * <p>发包使用 `BUDDYLIST` 的整表刷新（mode 7）：已接受好友带在线频道，离线为 -1。
 * 加好友时另发 mode 9 请求包给在线目标。分组暂不持久化，统一使用默认分组。
 */
@Log4j2
public final class BuddyHandler implements PacketHandler {
    private static final String DEFAULT_GROUP = "Default Group";
    private static final int NAME_LENGTH = 13;
    private static final int NAME_LIMIT = 13;

    /** 好友整表条目；{@code channel} 为 -1 表示离线。 */
    public record BuddyEntry(long characterId, String name, int channel, String group) { }

    private record Target(long id, String name) { }

    private record AddResult(long targetId, List<BuddyEntry> entries) { }

    private record Notice(long ownerId, List<BuddyEntry> entries) { }

    private record InitResult(List<BuddyEntry> ownEntries, List<Notice> notices) { }

    private SerialTaskQueue io;
    private final int channelId;
    private final IntercoordService intercoord;
    private final EventBus eventBus;
    private final PlayerSessionRegistry sessions;
    private final BuddyListRepository buddyRepo;
    private final PlayerCharacterRepository characters;

    public BuddyHandler(int channelId, IntercoordService intercoord, EventBus eventBus,
                        PlayerSessionRegistry sessions, BuddyListRepository buddyRepo) {
        this(channelId, intercoord, eventBus, sessions, buddyRepo, null);
    }

    public BuddyHandler(int channelId, IntercoordService intercoord, EventBus eventBus,
                        PlayerSessionRegistry sessions, BuddyListRepository buddyRepo,
                        PlayerCharacterRepository characters) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.eventBus = eventBus;
        this.sessions = sessions;
        this.buddyRepo = buddyRepo;
        this.characters = characters;
    }

    public BuddyHandler async(SerialTaskQueue io) {
        this.io = io;
        return this;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.buddy.outside_stage"));
            return;
        }
        PlayerCharacter character = session.getAttr("character");
        if (character == null) {
            session.close(I18n.message("error.buddy.not_in_map"));
            return;
        }
        if (packet.available() < 1) return;
        int mode = packet.readByte() & 0xFF;
        if (mode == 1) {
            if (packet.available() < 4) return;
            String name = packet.readString();
            String group = packet.available() >= 2 ? packet.readString() : DEFAULT_GROUP;
            add(session, character, name, group);
        } else if (mode == 2 && packet.available() >= 4) {
            accept(session, character, packet.readInt());
        } else if (mode == 3 && packet.available() >= 4) {
            delete(session, character, packet.readInt());
        }
    }

    /**
     * 登录完成后推送整表，并刷新在线好友的好友列表（同频道上下线通知）。
     *
     * <p>没有 IO 队列时直接读库（仅测试/兼容入口）。
     */
    public void initialize(PacketSession session) {
        PlayerCharacter character = session.getAttr("character");
        if (character == null) return;
        long playerId = character.getId();
        offload(() -> new InitResult(list(playerId), notices(playerId)), result -> {
            if (sessions.get(playerId) == session) session.send(buddyListPacket(result.ownEntries()));
            sendNotices(result.notices());
        });
    }

    /** 断线/离开频道后刷新在线好友的整表，让好友看到离线状态。 */
    public void offline(PacketSession session) {
        PlayerCharacter character = session.getAttr("character");
        if (character == null) return;
        long playerId = character.getId();
        offload(() -> notices(playerId), this::sendNotices);
    }

    private void sendNotices(List<Notice> notices) {
        for (Notice notice : notices) {
            PacketSession target = sessions.get(notice.ownerId());
            if (target != null) target.send(buddyListPacket(notice.entries()));
        }
    }

    /** 把 {@code onlineId} 作为好友的在线玩家列表刷新；只通知当前频道在线的好友。 */
    private List<Notice> notices(long onlineId) {
        List<Notice> notices = new ArrayList<>();
        for (BuddyListEntity row : buddyRepo.findByBuddy(onlineId)) {
            if (!BuddyListEntity.ACCEPTED.equals(row.getStatus())) continue;
            long ownerId = row.getOwnerId();
            if (sessions.get(ownerId) == null) continue;
            notices.add(new Notice(ownerId, list(ownerId)));
        }
        return notices;
    }

    private void add(PacketSession session, PlayerCharacter character, String name, String group) {
        if (name == null || name.length() < 2 || name.length() > NAME_LIMIT
                || name.equals(character.getName())) return;
        String useGroup = group == null || group.isBlank() || group.length() > 16 ? DEFAULT_GROUP : group;
        long ownerId = character.getId();
        String ownerName = character.getName();
        int capacity = character.getBuddyCapacity();
        offload(() -> {
            Target target = findCharacter(name);
            if (target == null) return new AddResult(0, list(ownerId));
            boolean existing = buddyRepo.exists(ownerId, target.id());
            if (!existing && acceptedCount(ownerId) >= capacity) {
                return new AddResult(0, list(ownerId));
            }
            buddyRepo.insertIfAbsent(row(ownerId, target.id(), BuddyListEntity.PENDING));
            buddyRepo.insertIfAbsent(row(target.id(), ownerId, BuddyListEntity.PENDING));
            return new AddResult(target.id(), list(ownerId));
        }, result -> {
            if (sessions.get(ownerId) != session) return;
            session.send(buddyListPacket(result.entries()));
            if (result.targetId() == 0) return;
            PacketSession target = sessions.get(result.targetId());
            if (target != null) {
                target.send(requestBuddylistAdd(ownerId, ownerName, result.targetId(), useGroup));
            }
        });
    }

    private void accept(PacketSession session, PlayerCharacter character, int buddyId) {
        if (buddyId <= 0) return;
        long ownerId = character.getId();
        offload(() -> {
            buddyRepo.updateStatus(ownerId, buddyId, BuddyListEntity.ACCEPTED);
            buddyRepo.updateStatus(buddyId, ownerId, BuddyListEntity.ACCEPTED);
            return new Lists(list(ownerId), list(buddyId), buddyId);
        }, result -> {
            if (sessions.get(ownerId) == session) session.send(buddyListPacket(result.ownerEntries()));
            PacketSession target = sessions.get(result.buddyId());
            if (target != null) target.send(buddyListPacket(result.buddyEntries()));
        });
    }

    private void delete(PacketSession session, PlayerCharacter character, int buddyId) {
        if (buddyId <= 0) return;
        long ownerId = character.getId();
        offload(() -> {
            buddyRepo.delete(ownerId, buddyId);
            buddyRepo.delete(buddyId, ownerId);
            return new Lists(list(ownerId), list(buddyId), buddyId);
        }, result -> {
            if (sessions.get(ownerId) == session) session.send(buddyListPacket(result.ownerEntries()));
            PacketSession target = sessions.get(result.buddyId());
            if (target != null) target.send(buddyListPacket(result.buddyEntries()));
        });
    }

    private record Lists(List<BuddyEntry> ownerEntries, List<BuddyEntry> buddyEntries, long buddyId) { }

    private int acceptedCount(long ownerId) {
        int count = 0;
        for (BuddyListEntity row : buddyRepo.findByOwner(ownerId)) {
            if (BuddyListEntity.ACCEPTED.equals(row.getStatus())) count++;
        }
        return count;
    }

    /** 读已接受好友并补全名字与在线频道；缺失角色记录时跳过。 */
    private List<BuddyEntry> list(long ownerId) {
        List<BuddyEntry> entries = new ArrayList<>();
        for (BuddyListEntity row : buddyRepo.findByOwner(ownerId)) {
            if (!BuddyListEntity.ACCEPTED.equals(row.getStatus())) continue;
            String name = nameOf(row.getBuddyId());
            if (name == null) continue;
            int channel = sessions.get(row.getBuddyId()) != null ? channelId : -1;
            entries.add(new BuddyEntry(row.getBuddyId(), name, channel, DEFAULT_GROUP));
        }
        return entries;
    }

    private String nameOf(long characterId) {
        PacketSession session = sessions.get(characterId);
        if (session != null) {
            PlayerCharacter character = session.getAttr("character");
            if (character != null) return character.getName();
        }
        if (characters == null) return null;
        Optional<PlayerCharacterRecord> record = characters.findById(characterId);
        return record.map(PlayerCharacterRecord::getName).orElse(null);
    }

    private Target findCharacter(String name) {
        for (PacketSession session : sessions.all()) {
            PlayerCharacter character = session.getAttr("character");
            if (character != null && character.getName().equals(name)) {
                return new Target(character.getId(), character.getName());
            }
        }
        if (characters == null) return null;
        return characters.findByName(name)
                .map(record -> new Target(record.getId(), record.getName()))
                .orElse(null);
    }

    private BuddyListEntity row(long owner, long buddy, String status) {
        BuddyListEntity entity = new BuddyListEntity();
        entity.setOwnerId(owner);
        entity.setBuddyId(buddy);
        entity.setStatus(status);
        return entity;
    }

    /** 有执行归属时走后台 IO + 版本门回包；否则同步执行（测试/无频道适配器）。 */
    private <T> void offload(Supplier<T> task, Consumer<T> completion) {
        if (io == null || sessions.execution() == null) {
            completion.accept(task.get());
            return;
        }
        long version = sessions.execution().version();
        io.submit(task).whenComplete((result, error) -> sessions.execution().continueAt(version, () -> {
            if (error != null) {
                log.error(I18n.message("log.social.io_failed"), error);
                return;
            }
            completion.accept(result);
        }));
    }

    /** v83 好友整表刷新：mode 7、条目 id/13 字节名字/状态/频道/13 字节分组/mapid。 */
    public static OutPacket buddyListPacket(List<BuddyEntry> entries) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.BUDDYLIST.getValue());
        packet.writeByte(7);
        packet.writeByte(entries.size());
        for (BuddyEntry entry : entries) {
            packet.writeInt((int) entry.characterId());
            writeFixedString(packet, entry.name(), NAME_LENGTH);
            packet.writeByte(0);
            packet.writeInt(entry.channel() < 0 ? -2 : entry.channel() - 1);
            writeFixedString(packet, entry.group(), NAME_LENGTH);
            packet.writeInt(0);
        }
        for (int i = 0; i < entries.size(); i++) {
            packet.writeInt(0);
        }
        return packet;
    }

    /** v83 加好友请求：mode 9、发起方 id/名字、固定字段和接收方 id。 */
    public static OutPacket requestBuddylistAdd(long fromId, String fromName, long toId, String group) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.BUDDYLIST.getValue());
        packet.writeByte(9);
        packet.writeInt((int) fromId);
        packet.writeString(fromName);
        packet.writeInt((int) fromId);
        writeFixedString(packet, fromName, 11);
        packet.writeByte(0x09);
        packet.writeByte(0xF0);
        packet.writeByte(0x01);
        packet.writeInt(0x0F);
        writeFixedString(packet, group == null ? DEFAULT_GROUP : group, NAME_LENGTH);
        packet.writeByte(0);
        packet.writeInt((int) toId);
        return packet;
    }

    private static void writeFixedString(OutPacket packet, String value, int length) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(InPacket.DEFAULT_CHARSET);
        packet.writeBytes(Arrays.copyOf(bytes, length));
    }
}
