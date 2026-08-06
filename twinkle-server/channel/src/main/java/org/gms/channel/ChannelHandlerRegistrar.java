package org.gms.channel;

import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;

/**
 * channel 模块 handler 装配（进图链路：登录进图 + 地图转移完成，贡献点版本化红线 13）。
 *
 * <p>bootstrap 装配时调用 {@link #register}。频道 id 与依赖经构造注入。
 * 实现类不加 @Singleton——由 bootstrap ChannelConfig 的 @Bean 统一装配，
 * 避免与 @Bean 双份产生 NonUniqueBeanException（同 m1-progress 的 Flex*Repository 教训）。
 */
public final class ChannelHandlerRegistrar {

    private final PlayerLoggedinHandler playerLoggedin;
    private final PlayerMapTransitionHandler mapTransition;

    public ChannelHandlerRegistrar(PlayerLoggedinHandler playerLoggedin,
                                   PlayerMapTransitionHandler mapTransition) {
        this.playerLoggedin = playerLoggedin;
        this.mapTransition = mapTransition;
    }

    /** 注册 M2 进图链路 handler。 */
    public void register(HandlerRegistry registry) {
        registry.register(RecvOpcode.PLAYER_LOGGEDIN, playerLoggedin);
        registry.register(RecvOpcode.PLAYER_MAP_TRANSFER, mapTransition);
    }
}
