package org.gms.channel;

import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;

/**
 * channel 模块 handler 装配（进图 + 移动/战斗/交易/NPC 对话/物品使用，贡献点版本化红线 13）。
 *
 * <p>bootstrap 装配时调用 {@link #register}。实现类不加 @Singleton——由 bootstrap
 * ChannelConfig 的 @Bean 统一装配，避免与 @Bean 双份产生 NonUniqueBeanException。
 */
public final class ChannelHandlerRegistrar {

    private final PlayerLoggedinHandler playerLoggedin;
    private final PlayerMapTransitionHandler mapTransition;
    private final MovePlayerHandler movePlayer;
    private final AttackHandler closeRange;
    private final AttackHandler ranged;
    private final AttackHandler magic;
    private final PlayerInteractionHandler interaction;
    private final NpcTalkHandler npcTalk;
    private final NpcTalkMoreHandler npcTalkMore;
    private final UseItemHandler useItem;
    private final WhisperHandler whisper;
    private final ChangeChannelHandler changeChannel;
    private final BuddyHandler buddy;
    private final MoveLifeHandler moveLife;

    public ChannelHandlerRegistrar(PlayerLoggedinHandler playerLoggedin,
                                   PlayerMapTransitionHandler mapTransition,
                                   MovePlayerHandler movePlayer,
                                   AttackHandler closeRange,
                                   AttackHandler ranged,
                                   AttackHandler magic,
                                   PlayerInteractionHandler interaction,
                                   NpcTalkHandler npcTalk,
                                   NpcTalkMoreHandler npcTalkMore,
                                   UseItemHandler useItem) {
        this(playerLoggedin, mapTransition, movePlayer, closeRange, ranged, magic, interaction,
                npcTalk, npcTalkMore, useItem, null, null, null, null);
    }

    public ChannelHandlerRegistrar(PlayerLoggedinHandler playerLoggedin,
                                   PlayerMapTransitionHandler mapTransition,
                                   MovePlayerHandler movePlayer,
                                   AttackHandler closeRange,
                                   AttackHandler ranged,
                                   AttackHandler magic,
                                   PlayerInteractionHandler interaction,
                                   NpcTalkHandler npcTalk,
                                   NpcTalkMoreHandler npcTalkMore,
                                   UseItemHandler useItem,
                                   WhisperHandler whisper,
                                   ChangeChannelHandler changeChannel,
                                   BuddyHandler buddy) {
        this(playerLoggedin, mapTransition, movePlayer, closeRange, ranged, magic, interaction,
                npcTalk, npcTalkMore, useItem, whisper, changeChannel, buddy, null);
    }

    public ChannelHandlerRegistrar(PlayerLoggedinHandler playerLoggedin,
                                   PlayerMapTransitionHandler mapTransition,
                                   MovePlayerHandler movePlayer,
                                   AttackHandler closeRange,
                                   AttackHandler ranged,
                                   AttackHandler magic,
                                   PlayerInteractionHandler interaction,
                                   NpcTalkHandler npcTalk,
                                   NpcTalkMoreHandler npcTalkMore,
                                   UseItemHandler useItem,
                                   WhisperHandler whisper,
                                   ChangeChannelHandler changeChannel,
                                   BuddyHandler buddy,
                                   MoveLifeHandler moveLife) {
        this.playerLoggedin = playerLoggedin;
        this.mapTransition = mapTransition;
        this.movePlayer = movePlayer;
        this.closeRange = closeRange;
        this.ranged = ranged;
        this.magic = magic;
        this.interaction = interaction;
        this.npcTalk = npcTalk;
        this.npcTalkMore = npcTalkMore;
        this.useItem = useItem;
        this.whisper = whisper;
        this.changeChannel = changeChannel;
        this.buddy = buddy;
        this.moveLife = moveLife;
    }

    /** 注册全部频道 handler（进图链路 + M3-5 游戏内协议 + M4 三机制玩法 + 阶段 B 怪物移动）。 */
    public void register(HandlerRegistry registry) {
        registry.register(RecvOpcode.PLAYER_LOGGEDIN, playerLoggedin);
        registry.register(RecvOpcode.PLAYER_MAP_TRANSFER, mapTransition);
        registry.register(RecvOpcode.MOVE_PLAYER, movePlayer);
        registry.register(RecvOpcode.CLOSE_RANGE_ATTACK, closeRange);
        registry.register(RecvOpcode.RANGED_ATTACK, ranged);
        registry.register(RecvOpcode.MAGIC_ATTACK, magic);
        registry.register(RecvOpcode.PLAYER_INTERACTION, interaction);
        registry.register(RecvOpcode.NPC_TALK, npcTalk);
        registry.register(RecvOpcode.NPC_TALK_MORE, npcTalkMore);
        registry.register(RecvOpcode.USE_ITEM, useItem);
        if (whisper != null) {
            registry.register(RecvOpcode.WHISPER, whisper);
        }
        if (changeChannel != null) {
            registry.register(RecvOpcode.CHANGE_CHANNEL, changeChannel);
        }
        if (buddy != null) {
            registry.register(RecvOpcode.BUDDYLIST_MODIFY, buddy);
        }
        if (moveLife != null) {
            registry.register(RecvOpcode.MOVE_LIFE, moveLife);
        }
    }
}
