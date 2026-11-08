package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.domain.game.PlayerCharacter;
import org.gms.event.EventBus;
import org.gms.i18n.I18n;
import org.gms.message.BuddyPresenceNotice;
import org.gms.message.BuddyRequest;
import org.gms.message.MessageTargets;
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
import org.gms.service.intercoord.PlayerPresenceService.PlayerPresence;

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
 *
 * <p>跨频道：好友的在线频道取自大区 Presence（{@link IntercoordService#presence}），
 * 不再只看本频道会话表。本频道无法直接给异频道玩家回包时，经消息总线把
 * {@link BuddyPresenceNotice}/{@link BuddyRequest} 投递到对方属主频道，由对方频道重建整表
 * 或下发申请包；本频道目标仍直接回包，不经总线。
 */
@Log4j2
public final class BuddyHandler implements PacketHandler, AutoCloseable {
    private static final String DEFAULT_GROUP = "Default Group";
    private static final int NAME_LENGTH = 13;
    private static final int NAME_LIMIT = 13;

    /** 好友整表条目；{@code channel} 为 -1 表示离线。 */
    public record BuddyEntry(long characterId, String name, int channel, String group) { }

    private record Target(long id, String name) { }

    private record AddResult(long targetId, List<BuddyEntry> entries, int targetChannel) { }

    /**
     * 一次好友列表刷新。{@code entries} 非空表示目标在本频道，直接回包；为空表示目标在其它
     * 频道，按 {@code channel} 投递通知，由对方频道重建整表。
     */
    private record Refresh(long ownerId, int channel, long peerId, int peerChannel, List<BuddyEntry> entries) { }

    private record InitResult(List<BuddyEntry> ownEntries, List<Refresh> refreshes) { }

    private SerialTaskQueue io;
    private final int channelId;
    private final IntercoordService intercoord;
    private final EventBus eventBus;
    private final PlayerSessionRegistry sessions;
    private final BuddyListRepository buddyRepo;
    private final PlayerCharacterRepository characters;
    private final AutoCloseable presenceSubscription;
    private final AutoCloseable requestSubscription;

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
        this.presenceSubscription = eventBus == null ? null
                : eventBus.subscribe(MessageTargets.channel(channelId), BuddyPresenceNotice.class, this::onPresenceNotice);
        this.requestSubscription = eventBus == null ? null
                : eventBus.subscribe(MessageTargets.channel(channelId), BuddyRequest.class, this::onAddRequest);
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
     * 登录完成后推送整表，并刷新在线好友的好友列表（含跨频道好友）。
     *
     * <p>没有 IO 队列时直接读库（仅测试/兼容入口）。
     */
    public void initialize(PacketSession session) {
        PlayerCharacter character = session.getAttr("character");
        if (character == null) return;
        long playerId = character.getId();
        offload(() -> new InitResult(list(playerId), refreshes(playerId, channelId)), result -> {
            if (sessions.get(playerId) == session) session.send(buddyListPacket(result.ownEntries()));
            dispatch(result.refreshes());
        });
    }

    /** 断线/离开频道后刷新在线好友的整表，让好友看到离线状态（含跨频道好友）。 */
    public void offline(PacketSession session) {
        PlayerCharacter character = session.getAttr("character");
        if (character == null) return;
        long playerId = character.getId();
        offload(() -> refreshes(playerId, -1), this::dispatch);
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
            if (target == null) return new AddResult(0, list(ownerId), -1);
            boolean existing = buddyRepo.exists(ownerId, target.id());
            if (!existing && acceptedCount(ownerId) >= capacity) {
                return new AddResult(0, list(ownerId), -1);
            }
            buddyRepo.insertIfAbsent(row(ownerId, target.id(), BuddyListEntity.PENDING));
            buddyRepo.insertIfAbsent(row(target.id(), ownerId, BuddyListEntity.PENDING));
            return new AddResult(target.id(), list(ownerId), channelOf(target.id()));
        }, result -> {
            if (sessions.get(ownerId) == session) {
                session.send(buddyListPacket(result.entries()));
            }
            if (result.targetId() == 0) return;
            PacketSession target = sessions.get(result.targetId());
            if (target != null) {
                target.send(requestBuddylistAdd(ownerId, ownerName, result.targetId(), useGroup));
            } else {
                sendRequest(result.targetChannel(), new BuddyRequest(ownerId, ownerName, result.targetId(), useGroup));
            }
        });
    }

    private void accept(PacketSession session, PlayerCharacter character, int buddyId) {
        if (buddyId <= 0) return;
        long ownerId = character.getId();
        offload(() -> {
            buddyRepo.updateStatus(ownerId, buddyId, BuddyListEntity.ACCEPTED);
            buddyRepo.updateStatus(buddyId, ownerId, BuddyListEntity.ACCEPTED);
            return List.of(refreshOf(ownerId, buddyId, channelOf(buddyId)),
                    refreshOf(buddyId, ownerId, channelId));
        }, this::dispatch);
    }

    private void delete(PacketSession session, PlayerCharacter character, int buddyId) {
        if (buddyId <= 0) return;
        long ownerId = character.getId();
        offload(() -> {
            buddyRepo.delete(ownerId, buddyId);
            buddyRepo.delete(buddyId, ownerId);
            // 双向关系已删除，双方重建整表后该条目自然消失，peerChannel 不再有意义。
            return List.of(refreshOf(ownerId, buddyId, -1), refreshOf(buddyId, ownerId, -1));
        }, this::dispatch);
    }

    /** 异频道加好友申请：交给目标属主频道向本地会话发 mode 9。 */
    private void onAddRequest(BuddyRequest request) {
        deliver(() -> {
            PacketSession target = sessions.get(request.toId());
            if (target != null) {
                target.send(requestBuddylistAdd(request.fromId(), request.fromName(),
                        request.toId(), request.group()));
            }
        });
    }

    /** 异频道上下线/关系变化：重建本频道该玩家的整表并回包。 */
    private void onPresenceNotice(BuddyPresenceNotice notice) {
        offload(() -> sessions.get(notice.ownerId()) == null
                        ? null
                        : list(notice.ownerId(), notice.peerId(), notice.peerChannel()),
                entries -> {
                    if (entries == null) return;
                    PacketSession target = sessions.get(notice.ownerId());
                    if (target != null) target.send(buddyListPacket(entries));
                });
    }

    /** 把刷新请求落到本频道回包，或经消息总线投递到好友属主频道。 */
    private void dispatch(List<Refresh> refreshes) {
        for (Refresh refresh : refreshes) dispatch(refresh);
    }

    private void dispatch(Refresh refresh) {
        if (refresh.entries() != null) {
            PacketSession target = sessions.get(refresh.ownerId());
            if (target != null) target.send(buddyListPacket(refresh.entries()));
            return;
        }
        if (eventBus == null || refresh.channel() <= 0 || refresh.channel() == channelId) return;
        eventBus.send(MessageTargets.channel(refresh.channel()),
                new BuddyPresenceNotice(refresh.ownerId(), refresh.peerId(), refresh.peerChannel()));
    }

    private void sendRequest(int channel, BuddyRequest request) {
        if (eventBus == null || channel <= 0 || channel == channelId) return;
        eventBus.send(MessageTargets.channel(channel), request);
    }

    /**
     * 构造一次刷新：目标在本频道时直接生成整表，否则只带路由信息。
     *
     * <p>必须在后台 IO 线程调用（会查 Presence）；本频道目标仍以物理会话表为准。
     */
    private Refresh refreshOf(long ownerId, long peerId, int peerChannel) {
        if (sessions.get(ownerId) != null) {
            return new Refresh(ownerId, channelId, peerId, peerChannel,
                    list(ownerId, peerId, peerChannel));
        }
        return new Refresh(ownerId, channelOf(ownerId), peerId, peerChannel, null);
    }

    /** 所有把 {@code changedId} 当好友的在线玩家（含异频道）的刷新请求。 */
    private List<Refresh> refreshes(long changedId, int changedChannel) {
        List<Refresh> refreshes = new ArrayList<>();
        for (BuddyListEntity row : buddyRepo.findByBuddy(changedId)) {
            if (!BuddyListEntity.ACCEPTED.equals(row.getStatus())) continue;
            refreshes.add(refreshOf(row.getOwnerId(), changedId, changedChannel));
        }
        return refreshes;
    }

    private void deliver(Runnable action) {
        if (sessions.execution() == null) {
            action.run();
            return;
        }
        if (sessions.execution().isClosed()) return;
        sessions.execution().execute(action);
    }

    private int acceptedCount(long ownerId) {
        int count = 0;
        for (BuddyListEntity row : buddyRepo.findByOwner(ownerId)) {
            if (BuddyListEntity.ACCEPTED.equals(row.getStatus())) count++;
        }
        return count;
    }

    private List<BuddyEntry> list(long ownerId) {
        return list(ownerId, 0, -1);
    }

    /**
     * 读已接受好友并补全名字与在线频道；缺失角色记录时跳过。
     *
     * <p>{@code peerId}/{@code peerChannel} 用于覆盖刚刚发生变化的条目：变化者的 Presence
     * 可能尚未登记或已注销，直接用调用方掌握的状态，避免整表读到旧值。
     */
    private List<BuddyEntry> list(long ownerId, long peerId, int peerChannel) {
        List<BuddyEntry> entries = new ArrayList<>();
        for (BuddyListEntity row : buddyRepo.findByOwner(ownerId)) {
            if (!BuddyListEntity.ACCEPTED.equals(row.getStatus())) continue;
            String name = nameOf(row.getBuddyId());
            if (name == null) continue;
            int channel = row.getBuddyId() == peerId ? peerChannel : channelOf(row.getBuddyId());
            entries.add(new BuddyEntry(row.getBuddyId(), name, channel, DEFAULT_GROUP));
        }
        return entries;
    }

    /**
     * 好友当前在线频道：本频道会话优先，其次查大区 Presence；查不到按离线处理。
     *
     * <p>Presence 查询失败不阻断整表刷新——协调者失联时展示为离线，下一次刷新会纠正。
     */
    private int channelOf(long characterId) {
        if (sessions.get(characterId) != null) return channelId;
        if (intercoord == null) return -1;
        try {
            Optional<PlayerPresence> presence = intercoord.presence(characterId);
            return presence == null ? -1 : presence.map(PlayerPresence::ownerChannelId).orElse(-1);
        } catch (RuntimeException error) {
            log.debug(I18n.message("log.buddy.presence_failed"), characterId, error);
            return -1;
        }
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

    @Override
    public void close() {
        closeQuietly(presenceSubscription);
        closeQuietly(requestSubscription);
    }

    private static void closeQuietly(AutoCloseable subscription) {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception error) {
            log.warn(I18n.message("log.buddy.unsubscribe_failed"), error);
        }
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
