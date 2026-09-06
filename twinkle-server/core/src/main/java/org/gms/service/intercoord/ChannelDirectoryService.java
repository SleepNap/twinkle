package org.gms.service.intercoord;

import java.util.Map;
import java.util.Optional;

/** 稀疏频道 ID 到实时 Worker 端点的目录端口。 */
public interface ChannelDirectoryService {
    void registerChannel(int channelId, String host, int port, int onlineCount);

    default void registerChannel(int channelId, String host, int port, int onlineCount, String workerId) {
        registerChannel(channelId, host, port, onlineCount);
    }

    default void unregisterChannel(int channelId) {
    }

    void heartbeatChannel(int channelId, int onlineCount);
    Optional<ChannelInfo> channel(int channelId);
    Map<Integer, ChannelInfo> channels();

    record ChannelInfo(int channelId, String host, int port, int onlineCount, String workerId) {
        public ChannelInfo(int channelId, String host, int port, int onlineCount) {
            this(channelId, host, port, onlineCount, "");
        }
    }
}
