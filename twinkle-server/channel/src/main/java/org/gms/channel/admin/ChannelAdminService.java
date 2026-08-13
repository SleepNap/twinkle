package org.gms.channel.admin;

import lombok.extern.log4j.Log4j2;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.PlayerStorage;
import org.gms.channel.persist.RestartService;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.PetItem;
import org.gms.domain.script.ScriptManager;
import org.gms.hotreload.RestartCoordinator;
import org.gms.i18n.I18n;
import org.gms.net.packet.PacketSession;
import org.gms.service.admin.AdminService;

/**
 * 频道侧 {@link AdminService} 实现（架构 M3-1 第②路：管理侧经 service 接口访问频道）。
 *
 * <p>只读快照经 DTO 拷贝返回（{@link Character} 是内存态权威对象，绝不出进程/出模块边界，
 * 防 http-api 直踩游戏内存）。踢下线走会话注册表关闭连接。
 *
 * <p>M5 运维操作（架构 M5-1）：脚本重载、L4 重启均委托频道侧具体组件执行——管理侧
 * （http-api/admin）不 import 本实现依赖的 ScriptManager/RestartService，只经本接口调用。
 * 重启在守护线程异步执行（不阻塞管理 API 线程），进程关停由 {@code restartProcess}
 * 承担（bootstrap 注入 {@code System.exit}）。
 *
 * <p>装配由 bootstrap 接线（本类不加 @Singleton，避免与 @Bean 双份）。
 */
@Log4j2
public final class ChannelAdminService implements AdminService {



    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final long channelId;
    private final ScriptManager scriptManager;
    private final RestartService restartService;
    private final RestartCoordinator restartCoordinator;
    private final Runnable restartProcess;

    public ChannelAdminService(PlayerStorage players, PlayerSessionRegistry sessions, long channelId,
                               ScriptManager scriptManager, RestartService restartService,
                               RestartCoordinator restartCoordinator, Runnable restartProcess) {
        this.players = players;
        this.sessions = sessions;
        this.channelId = channelId;
        this.scriptManager = scriptManager;
        this.restartService = restartService;
        this.restartCoordinator = restartCoordinator;
        this.restartProcess = restartProcess;
    }

    @Override
    public ChannelSummary onlineSummary() {
        java.util.List<OnlinePlayer> snapshot = players.all().stream()
                .map(this::toDto)
                .sorted((a, b) -> Long.compare(a.characterId(), b.characterId()))
                .toList();
        return new ChannelSummary(snapshot.size(), channelId, snapshot);
    }

    @Override
    public PlayerInventory inventorySnapshot(long characterId) {
        Character character = players.getById(characterId);
        if (character == null) {
            return null;
        }
        synchronized (character) {
            java.util.List<InventoryItemView> items = new java.util.ArrayList<>();
            for (InventoryType inventoryType : InventoryType.values()) {
                if (inventoryType == InventoryType.UNDEFINED) {
                    continue;
                }
                for (Item item : character.getInventory(inventoryType).items()) {
                    items.add(toItemView(inventoryType, item));
                }
            }
            items.sort(java.util.Comparator.comparingInt(InventoryItemView::inventoryType)
                    .thenComparingInt(InventoryItemView::position));
            return new PlayerInventory(character.getId(),
                    character.getName() == null ? "" : character.getName(),
                    character.dirtyVersion(), java.util.List.copyOf(items));
        }
    }

    @Override
    public boolean kick(long characterId) {
        PacketSession session = sessions.get(characterId);
        if (session == null) {
            return false;
        }
        session.close(I18n.message("error.admin.kick", characterId));
        // 断链注销由 DisconnectListener 完成（ChannelServer 装配），此处只触发关闭。
        return true;
    }

    @Override
    public int reloadScripts() {
        int changed = scriptManager.reload();
        log.info(I18n.message("log.admin.reload_scripts"), changed);
        return changed;
    }

    @Override
    public void requestRestart() {
        log.info(I18n.message("log.admin.restart_requested"));
        Thread daemon = new Thread(() -> {
            try {
                restartService.restart(restartProcess);
            } catch (Exception e) {
                log.error(I18n.message("log.admin.restart_error"), e);
            }
        }, "admin-requested-restart");
        daemon.setDaemon(true);
        daemon.start();
    }

    @Override
    public RestartCoordinator.Phase restartPhase() {
        return restartCoordinator.phase();
    }

    private OnlinePlayer toDto(Character chr) {
        return new OnlinePlayer(chr.getId(), chr.getName(), chr.getMap(), chr.getLevel(), chr.getJob());
    }

    private static InventoryItemView toItemView(InventoryType inventoryType, Item item) {
        EquipView equipView = null;
        PetView petView = null;
        String itemType = "item";
        if (item instanceof Equip equip) {
            itemType = "equip";
            equipView = new EquipView(
                    equip.getUpgradeSlots(), equip.getLevel(), equip.getStr(), equip.getDex(),
                    equip.getIntStat(), equip.getLuk(), equip.getHp(), equip.getMp(), equip.getWatk(),
                    equip.getMatk(), equip.getWdef(), equip.getMdef(), equip.getAcc(), equip.getAvoid(),
                    equip.getHands(), equip.getSpeed(), equip.getJump(), equip.getVicious(),
                    equip.getItemLevel(), equip.getItemExp(), equip.getRingId());
        } else if (item instanceof PetItem pet) {
            itemType = "pet";
            petView = new PetView(
                    pet.getPetName() == null ? "" : pet.getPetName(),
                    pet.getPetLevel(), pet.getCloseness(), pet.getFullness(),
                    pet.getPetAttribute(), pet.getPetSkill(), pet.getRemainLife(), pet.getAttribute());
        }
        return new InventoryItemView(
                inventoryType.getType(), item.getPosition(), itemType, item.getId(), item.getQuantity(),
                item.getCashId(), item.getPetId(), item.getOwner() == null ? "" : item.getOwner(),
                item.getFlag(), item.getExpiration(), equipView, petView);
    }
}
