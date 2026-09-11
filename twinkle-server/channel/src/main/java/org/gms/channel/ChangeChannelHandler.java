package org.gms.channel;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import lombok.extern.log4j.Log4j2;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.concurrent.ThreadManager;
import org.gms.domain.game.PlayerCharacter;
import org.gms.event.ReliableEventBus;
import org.gms.i18n.I18n;
import org.gms.message.ChangeChannelRequest;
import org.gms.message.MessageTargets;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.net.packet.v83.V83ChannelId;
import org.gms.service.intercoord.ChannelDirectoryService;
import org.gms.service.intercoord.IntercoordService;



/**
 * 换频道处理（RecvOpcode.CHANGE_CHANNEL 0x27，架构 4.7：一机制两用）。
 *
 * <p>功能性：玩家换频道 = 经消息总线发 CC 请求 → 定位表更新 → 老频道下线清 map → 目标频道
 * 注册 → 客户端重连（v83 换频道本来就是 loading 界面）。兜底性：升级前把玩家挪到别的频道
 * （MAINTENANCE reason）→ 重启 → 回来，玩家视角只是"换了一次频道"。
 *
 * <p>v83 收包：opcode(2) + 4B 头 + 目标频道（1B，0-based）。M6 跨进程：发送前<b>等待异步存档完成</b>
 * （玩家状态落 DB，目标频道重连后从 DB 加载最新态，不掉数据）；目标频道经
 * {@link ChannelChangeReceiver} 消费 CC 请求（恰好一次）。
 */
@Log4j2
public final class ChangeChannelHandler implements PacketHandler {



    private SerialTaskQueue presenceIo;
    public ChangeChannelHandler asyncPresence(SerialTaskQueue io) { this.presenceIo = io; return this; }

    private final int channelId;
    private final IntercoordService intercoord;
    private final ReliableEventBus reliableBus;
    private final PlayerSessionRegistry sessions;
    private final PlayerStorage players;
    private final CharacterSaveQueue saveQueue;
    private final ThreadManager background;

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions) {
        this(channelId, intercoord, reliableBus, sessions, null, null);
    }

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions, CharacterSaveQueue saveQueue) {
        this(channelId, intercoord, reliableBus, sessions, null, saveQueue);
    }

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions, PlayerStorage players,
                                CharacterSaveQueue saveQueue) {
        this(channelId, intercoord, reliableBus, sessions, players, saveQueue, null);
    }

    public ChangeChannelHandler(int channelId, IntercoordService intercoord, ReliableEventBus reliableBus,
                                PlayerSessionRegistry sessions, PlayerStorage players,
                                CharacterSaveQueue saveQueue, ThreadManager background) {
        this.channelId = channelId;
        this.intercoord = intercoord;
        this.reliableBus = reliableBus;
        this.sessions = sessions;
        this.players = players;
        this.saveQueue = saveQueue;
        this.background = background;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.channel.change.outside_stage"));
            return;
        }
        PlayerCharacter chr = session.getAttr("character");
        if (chr == null) {
            session.close(I18n.message("error.channel.change.not_in_map"));
            return;
        }

        // 解析：4B 头 + 1B 目标频道（0-based）
        if (packet.available() < 5) {
            return;
        }
        packet.skip(4);
        int targetChannel = packet.readByte() & 0xFF; // 0-based
        int targetId = V83ChannelId.fromWire(targetChannel);

        if (targetId == channelId) {
            return; // 同频道，忽略
        }
        if (sessions.get(chr.getId()) != session || session.getAttr("stateTransfer") != null) return;
        if (sessions.execution() != null) {
            transferAsync(session, chr, targetId);
            return;
        }
        ChannelDirectoryService.ChannelInfo target = intercoord.channel(targetId).orElse(null);
        if (target == null) {
            log.warn("Target channel is unavailable: {}", targetId);
            return;
        }
        byte[] targetIp = resolveIpv4(target.host());

        // 发送前同步存档（架构 4.7：老频道 flush 状态 → 目标频道加载）。玩家状态落 DB，
        // 目标频道重连后 PlayerLoggedinHandler 从 DB 加载最新态（跨进程不掉数据）。
        if (saveQueue != null) {
            saveQueue.flushCharacterSync(chr);
        }

        // 发 CC 请求（经可靠总线发目标频道，架构 4.5：CC 迁移不掉数据、不重复的核心）。
        // 单一属主序号流：每玩家的 CC 请求流内单调，进程崩了重投未 ACKED。
        ChangeChannelRequest req = new ChangeChannelRequest(chr.getId(), channelId, targetId,
                ChangeChannelRequest.Reason.PLAYER_CHANGE);
        log.info(I18n.message("log.channel.change.request"), chr.getName(), channelId, targetId, req.reason());
        // 必须等可靠帧真正写出；coordinator 断链时保持玩家在原频道，不能先进入迁移态。
        reliableBus.send("cc:player:" + chr.getId(), MessageTargets.channel(targetId), req).join();

        // 开始迁移：旧频道仍是 TCP 属主；目标频道只有在客户端重连并完成 PlayerLoggedin 后
        // 才能登记为新属主，避免“尚未连接目标频道但定位已过去”的幽灵在线。
        intercoord.beginChannelTransfer(chr.getId(), channelId, targetId);
        if (chr.getMapObject() != null) {
            chr.getMapObject().removeCharacter(chr);
        }
        if (players != null) {
            players.remove(chr);
        }
        sessions.unregister(chr.getId(), session);
        session.transition(SessionStage.CHANNEL_TRANSITION);
        session.redirect(ChannelPacketFactory.changeChannel(targetIp, target.port()))
                .exceptionally(error -> { session.close(I18n.message("error.channel.transfer_closed")); return null; });
        // 玩家重连目标频道端口 → PlayerLoggedinHandler 重新进图（v83 loading 界面）
        log.info(I18n.message("log.channel.change.complete"), chr.getName(), targetId, targetId);
    }

    private static byte[] resolveIpv4(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address instanceof Inet4Address) return address.getAddress();
        } catch (Exception e) {
            throw new IllegalStateException("Target channel host is not an IPv4 address: " + host, e);
        }
        throw new IllegalStateException("Target channel host is not an IPv4 address: " + host);
    }

    private record Destination(ChannelDirectoryService.ChannelInfo channel, byte[] address) { }

    private void transferAsync(PacketSession session, PlayerCharacter character, int targetId) {
        if (background == null) throw new IllegalStateException(I18n.message("error.channel.background_missing"));
        Object token = new Object();
        Runnable cancelTrade = session.getAttr("cancelTrade");
        if (cancelTrade != null) cancelTrade.run();
        NpcTalkHandler.closeConversation(session);
        session.setAttr("stateTransfer", token);
        long characterId = character.getId();
        CompletableFuture<Destination> work = background.supplyAsync(() -> {
            var target = intercoord.channel(targetId).orElseThrow(() -> new IllegalStateException(I18n.message("error.channel.target_unavailable")));
            return new Destination(target, resolveIpv4(target.host()));
        }).thenCompose(destination -> saveQueue.saveAsync(character).thenApply(ignored -> destination))
                .thenComposeAsync(destination -> {
                    if (Boolean.TRUE.equals(session.getAttr("transportClosed")))
                        return CompletableFuture.failedFuture(new IllegalStateException(I18n.message("error.channel.transfer_closed")));
                    var request = new ChangeChannelRequest(characterId, channelId, targetId,
                            ChangeChannelRequest.Reason.PLAYER_CHANGE);
                    return reliableBus.send("cc:player:" + characterId, MessageTargets.channel(targetId), request)
                            .thenApply(ignored -> destination);
                }, background).thenCompose(destination -> {
                    Runnable transfer = () -> intercoord.beginChannelTransfer(characterId, channelId, targetId);
                    return (presenceIo == null ? background.runAsync(transfer) : presenceIo.run(transfer))
                            .thenApply(ignored -> destination);
                });
        work.whenComplete((destination, error) -> sessions.execution().execute(() -> {
            if (sessions.get(characterId) != session || session.getAttr("stateTransfer") != token) return;
            if (error != null) {
                session.setAttr("stateTransfer", null);
                session.send(GameplayPackets.enableActions());
                log.error(I18n.message("log.channel.transfer_failed"), error);
                return;
            }
            if (character.getMapObject() != null) character.getMapObject().removeCharacter(character);
            if (players != null) players.remove(character);
            sessions.unregister(characterId, session);
            session.transition(SessionStage.CHANNEL_TRANSITION);
            session.redirect(ChannelPacketFactory.changeChannel(destination.address(), destination.channel().port()))
                    .exceptionally(failure -> {
                        log.error(I18n.message("log.channel.redirect_failed"), failure);
                        session.close(I18n.message("error.channel.transfer_closed"));
                        return null;
                    });
        }));
    }
}
