package org.gms.login;
import org.gms.service.intercoord.ChannelDirectoryService;

import jakarta.inject.Singleton;
import org.gms.service.intercoord.IntercoordService;
import org.gms.net.packet.v83.V83ChannelId;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 登录协议的频道目录：频道 ID 是稳定标识，列表位置和端口均不参与身份推导。 */
@Singleton
public final class ChannelSelectionService {

    public static final String SESSION_WORLD_ID = "selectedWorldId";
    public static final String SESSION_CHANNEL_ID = "selectedChannelId";

    public record Endpoint(int channelId, String host, int port, int onlineCount) {
        public int wireChannelId() { return V83ChannelId.toWire(channelId); }

        public byte[] ipv4() {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address instanceof Inet4Address) return address.getAddress();
            } catch (UnknownHostException ignored) {
                // 统一转为带端点上下文的异常。
            }
            throw new IllegalStateException("Channel host is not an IPv4 address: " + host);
        }
    }

    private final IntercoordService intercoord;

    public ChannelSelectionService(IntercoordService intercoord) {
        this.intercoord = intercoord;
    }

    public List<Endpoint> availableChannels() {
        return intercoord.channels().values().stream()
                .map(ChannelSelectionService::toEndpoint)
                .sorted(Comparator.comparingInt(Endpoint::channelId))
                .toList();
    }

    public Optional<Endpoint> channel(int channelId) {
        return intercoord.channel(channelId).map(ChannelSelectionService::toEndpoint);
    }

    public Optional<Endpoint> firstAvailable() {
        return availableChannels().stream().findFirst();
    }

    /** v83 wire channel 是无符号 1 字节且使用 internalId-1。 */
    public static int fromWireId(int wireChannelId) {
        return V83ChannelId.fromWire(wireChannelId);
    }

    private static Endpoint toEndpoint(ChannelDirectoryService.ChannelInfo info) {
        V83ChannelId.validateInternal(info.channelId());
        return new Endpoint(info.channelId(), info.host(), info.port(), info.onlineCount());
    }
}
