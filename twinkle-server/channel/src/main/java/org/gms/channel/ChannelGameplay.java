package org.gms.channel;

import org.gms.domain.game.lease.ControllerLeaseService;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.domain.script.ScriptManager;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;
import org.gms.domain.game.logic.ItemSystem;
import org.gms.domain.game.logic.PartySystem;
import org.gms.domain.game.logic.QuestSystem;
import org.gms.domain.game.logic.ProgressionSystem;
import org.gms.domain.game.logic.AvatarSystem;
import org.gms.domain.game.logic.ControlsSystem;
import org.gms.domain.game.logic.EquipmentSystem;
import org.gms.wz.WzResourceRegistry;
import java.time.Clock;
import org.gms.tick.TickHandler;
import org.gms.tick.TickScheduler;

/** 每频道游戏入口与掉落生命周期装配；不共享玩家、地图、商店会话或掉落状态。 */
public final class ChannelGameplay implements AutoCloseable {
    private final GroundDropService drops;
    private final PartyHandler parties;
    private final TickScheduler scheduler;
    private final TickHandler expiration;

    public ChannelGameplay(HandlerRegistry handlers, ChannelMapManager maps, MonsterSpawnService monsters,
                           ControllerLeaseService leases, int channelId, GameDataProvider data,
                           PlayerSessionRegistry sessions, ItemSystem items, QuestSystem quests,
                           ScriptManager scripts, NpcShopCatalog shopCatalog, TickScheduler scheduler,
                           WzResourceRegistry resources, ProgressionSystem progression, AvatarSystem avatarLogic,
                           ControlsSystem controlsLogic, EquipmentSystem equipmentLogic, PartySystem partyLogic) {
        this.scheduler = scheduler;
        this.drops = new GroundDropService(items, data, sessions, Clock.systemUTC());
        this.parties = new PartyHandler(partyLogic, sessions, channelId, Clock.systemUTC(), progression::accepts);
        ActiveSkillHandler skills = new ActiveSkillHandler(resources, progression, Clock.systemUTC(), sessions);
        AvatarHandler avatars = new AvatarHandler(sessions, avatarLogic, data, Clock.systemUTC());
        ControlsHandler controls = new ControlsHandler(sessions, controlsLogic, Clock.systemUTC());
        EquipmentService equipment = new EquipmentService(equipmentLogic, sessions, Clock.systemUTC());
        long sweepTicks = scheduler.ticksFor(1000);
        this.expiration = count -> { if (count % sweepTicks == 0) {
            drops.expire(); parties.refresh(); sessions.all().forEach(skills::expire); sessions.all().forEach(avatars::refresh);
            sessions.all().forEach(equipment::refresh);
        } };
        MapTransitionService transitions = new MapTransitionService(maps::getMap, monsters, leases, channelId, sessions);
        NpcShopHandler shops = new NpcShopHandler(shopCatalog, data, items);
        QuestActionHandler questActions = new QuestActionHandler(resources, quests);
        var login = handlers.find(RecvOpcode.PLAYER_LOGGEDIN.getValue()).orElseThrow();
        handlers.replace(RecvOpcode.PLAYER_LOGGEDIN, (session, packet) -> {
            session.setAttr("afterLogin", (Runnable) () -> {
                session.setAttr("questActions", questActions);
                var character = GameplaySession.character(session);
                if (character != null && character.getParty() != 0) { character.setParty(0); character.markDirty(); }
                controls.initialize(session);
                equipment.initialize(session);
            });
            login.handle(session, packet);
        }, 2);
        handlers.register(RecvOpcode.PARTY_OPERATION, parties);
        handlers.register(RecvOpcode.MULTI_CHAT, parties::chat);
        handlers.register(RecvOpcode.CHANGE_KEYMAP, controls::keys);
        handlers.register(RecvOpcode.SKILL_MACRO, controls::macros);
        handlers.register(RecvOpcode.CHANGE_QUICKSLOT, controls::quickSlots);
        handlers.register(RecvOpcode.CHAR_INFO_REQUEST, new CharacterInfoHandler(sessions));
        handlers.register(RecvOpcode.MESO_DROP, new MesoDropHandler(sessions, drops));
        handlers.register(RecvOpcode.FACE_EXPRESSION, avatars::express);
        handlers.register(RecvOpcode.USE_CHAIR, avatars::sit);
        handlers.register(RecvOpcode.CANCEL_CHAIR, avatars::stand);
        handlers.register(RecvOpcode.DENY_PARTY_REQUEST, (session, packet) -> parties.deny(session));
        handlers.register(RecvOpcode.QUEST_ACTION, questActions);
        handlers.register(RecvOpcode.SPECIAL_MOVE, skills);
        handlers.register(RecvOpcode.CANCEL_BUFF, skills::cancel);
        handlers.register(RecvOpcode.DISTRIBUTE_AP, new ProgressionHandler(progression, resources, false));
        handlers.register(RecvOpcode.AUTO_DISTRIBUTE_AP, new AutoApHandler(progression));
        handlers.register(RecvOpcode.DISTRIBUTE_SP, new ProgressionHandler(progression, resources, true));
        handlers.register(RecvOpcode.CHANGE_MAP, new ChangeMapHandler(transitions, false));
        handlers.register(RecvOpcode.CHANGE_MAP_SPECIAL, new ChangeMapHandler(transitions, true));
        handlers.register(RecvOpcode.USE_INNER_PORTAL, new ChangeMapHandler(transitions, true));
        handlers.replace(RecvOpcode.PLAYER_MAP_TRANSFER, (session, packet) -> {
            new PlayerMapTransitionHandler().handle(session, packet);
            sessions.visibility().enter(session);
            drops.enter(session);
        }, 2);
        handlers.register(RecvOpcode.ITEM_MOVE, new InventoryMoveHandler(items, drops, equipment, sessions));
        handlers.register(RecvOpcode.ITEM_PICKUP, new ItemPickupHandler(drops));
        handlers.register(RecvOpcode.NPC_SHOP, shops);
        handlers.replace(RecvOpcode.NPC_TALK, new NpcTalkHandler(scripts, items, quests, transitions, shops), 2);
        scheduler.register(expiration);
    }

    @Override public void close() {
        scheduler.unregister(expiration);
        drops.close();
        parties.close();
    }
}
