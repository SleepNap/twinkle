package org.gms.service.intercoord;
import java.util.Optional;


/** 玩家在大区内的在线真值与连接属主端口。 */
public interface PlayerPresenceService {
    void registerPlayer(long playerId, int worldId, int ownerChannelId);

    default void registerPlayer(long playerId, int ownerChannelId) {
        registerPlayer(playerId, 0, ownerChannelId);
    }

    void unregisterPlayer(long playerId);
    /** 旧频道的迟到注销/活动通知必须由协调者原子校验属主。 */
    default void unregisterOwnedPlayer(long playerId, int ownerChannelId) {
        throw new UnsupportedOperationException("Owned presence mutation is not implemented");
    }
    default void updateOwnedPlayerActivity(long playerId, int ownerChannelId, PlayerActivity activity) {
        throw new UnsupportedOperationException("Owned presence mutation is not implemented");
    }
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
