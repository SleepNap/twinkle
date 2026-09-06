package org.gms.service.intercoord;

import java.util.Optional;

/** 玩家在大区内的在线真值与连接属主端口。 */
public interface PlayerPresenceService {
    void registerPlayer(long playerId, int worldId, int ownerChannelId);

    default void registerPlayer(long playerId, int ownerChannelId) {
        registerPlayer(playerId, 0, ownerChannelId);
    }

    void unregisterPlayer(long playerId);
    void movePlayer(long playerId, int channelId);
    void beginChannelTransfer(long playerId, int sourceChannelId, int targetChannelId);
    void updatePlayerActivity(long playerId, PlayerActivity activity);
    Optional<PlayerPresence> presence(long playerId);
    Optional<Integer> locate(long playerId);
    int onlineOnChannel(int channelId);
    int sessionsOnChannel(int channelId);
    int onlineInWorld(int worldId);

    enum PlayerActivity {
        IN_CHANNEL, CASH_SHOP, MTS, CHANNEL_TRANSITION
    }

    record PlayerPresence(long playerId, int worldId, int ownerChannelId,
                          PlayerActivity activity, Integer targetChannelId) {
    }
}
