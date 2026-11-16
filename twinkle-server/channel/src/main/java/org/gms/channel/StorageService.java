package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.concurrent.SerialTaskQueue;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.logic.ItemSystem;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.i18n.I18n;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.v83.V83EquipStats;
import org.gms.net.packet.v83.V83ItemSnapshot;
import org.gms.net.packet.v83.V83PetStats;
import org.gms.persistence.entity.StorageItemEntity;
import org.gms.persistence.entity.StorageRecordEntity;
import org.gms.persistence.repo.StorageItemRepository;
import org.gms.persistence.repo.StorageRecordRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 账号仓库（NPC 存储/trunk）服务端实现。
 *
 * <p>仓库属账号而不是角色：同一账号的所有角色共享同一份 {@code storage_records} 头与
 * {@code storage_items} 行。频道内存态是权威，落库只做持久化：每次变更先在频道线程改内存并
 * 同步背包，再把不可变快照交给后台 IO 队列覆盖写入，写入失败只记录日志并在下次打开时重读。
 *
 * <p>协议侧：开窗包由 NPC 脚本经 {@code cm.openStorage(npcId)} 主动下发；收包 mode 4/5/6/7/8
 * 分别对应取出、存入、整理、金币搬运与关闭，解析与回包由 {@link StorageHandler} 和
 * {@link StoragePackets} 负责，本类只处理业务规则。
 *
 * <p>边界：仓库物品不写位置字段，客户端靠「该类型内顺序下标」识别，因此内存列表必须始终按
 * ({@code inventory_type}, {@code slot_index}) 排序，任何增删后都要重排 {@code slot_index}，
 * 否则会取出另一件物品。物品实例一律走 {@link ItemSystem} 的精确实例通道，禁止按 itemId 增删。
 */
@Log4j2
public final class StorageService {
    /** 会话属性名：当前打开的仓库会话（值为 {@link StorageSession}）。 */
    public static final String SESSION_ATTR = "storageSession";

    private static final int MIN_LEVEL = 15;
    private static final int DEFAULT_SLOTS = 4;
    private static final int MAX_SLOTS = 48;
    /** 每次存入收取的金币（v83 由 NPC WZ 的 trunkPut 决定，缺省 100）。 */
    private static final int PUT_FEE = 100;

    /** 一次已打开的仓库会话：记录触发它的 NPC，换图或关闭后失效。 */
    public record StorageSession(int npcId) { }

    /** 频道内存中的仓库状态；只在频道执行上下文中读写。 */
    private static final class State {
        private final long recordId;
        private int slotCount;
        private int version;
        private int meso;
        private final List<StorageItemEntity> items = new ArrayList<>();

        private State(long recordId, int slotCount, int version, int meso) {
            this.recordId = recordId;
            this.slotCount = slotCount;
            this.version = version;
            this.meso = meso;
        }
    }

    private final int worldId;
    private final StorageRecordRepository records;
    private final StorageItemRepository items;
    private final SerialTaskQueue io;
    private final PlayerSessionRegistry sessions;
    private final ItemSystem itemSystem;
    private final GameDataProvider data;
    /** accountId → 仓库状态；同账号多角色同频道共享，跨频道由 row_version 兜底。 */
    private final Map<Long, State> cache = new HashMap<>();

    public StorageService(int worldId, StorageRecordRepository records, StorageItemRepository items,
                          SerialTaskQueue io, PlayerSessionRegistry sessions, ItemSystem itemSystem,
                          GameDataProvider data) {
        this.worldId = worldId;
        this.records = records;
        this.items = items;
        this.io = io;
        this.sessions = sessions;
        this.itemSystem = itemSystem;
        this.data = data;
    }

    /**
     * 打开仓库：等级不足静默拒绝，缓存未命中时后台读库再回包。
     *
     * <p>必须在频道线程调用；回包同样回到房间线程，断线后的旧结果不会再发给新会话。
     */
    public void open(PacketSession session, PlayerCharacter character, int npcId) {
        if (character.getLevel() < MIN_LEVEL) return;
        if (npcId <= 0) return;
        long accountId = accountIdOf(character);
        if (accountId <= 0) return;
        State cached = cache.get(accountId);
        if (cached != null) {
            session.setAttr(SESSION_ATTR, new StorageSession(npcId));
            session.send(StoragePackets.open(npcId, cached.slotCount, cached.meso, snapshotsOf(cached)));
            return;
        }
        offload(() -> load(accountId), state -> {
            if (state == null) return;
            cache.put(accountId, state);
            if (sessions.get(character.getId()) != session) return;
            session.setAttr(SESSION_ATTR, new StorageSession(npcId));
            session.send(StoragePackets.open(npcId, state.slotCount, state.meso, snapshotsOf(state)));
        });
    }

    /** 取出：按「该类型内顺序下标」定位物品，先过背包容量再给物品。 */
    public void takeOut(PacketSession session, PlayerCharacter character, int type, int slot) {
        State state = active(session, character);
        if (state == null) return;
        InventoryType inventoryType = InventoryType.getByType((byte) type);
        if (inventoryType == InventoryType.UNDEFINED) return;
        List<StorageItemEntity> typed = typedItems(state, inventoryType);
        if (slot < 0 || slot >= typed.size()) return;
        StorageItemEntity target = typed.get(slot);
        TradeItemSnapshot incoming = toSnapshot(target);
        if (!itemSystem.canExchangeTradeItems(character, List.of(), List.of(incoming))) {
            session.send(StoragePackets.error(StoragePackets.ERROR_BAG_FULL));
            return;
        }
        Map<Short, V83ItemSnapshot> before = GameplayPackets.inventory(character, inventoryType);
        if (!itemSystem.giveTradeItems(character, List.of(incoming))) {
            session.send(StoragePackets.error(StoragePackets.ERROR_BAG_FULL));
            return;
        }
        state.items.remove(target);
        renumber(state);
        persist(accountIdOf(character), state);
        session.send(StoragePackets.takeOut(state.slotCount, typeBitfield(inventoryType),
                snapshotsOf(state, inventoryType), type));
        sendBagChanges(session, character, inventoryType, before);
    }

    /** 存入：先扣背包与费用，再进仓库；仓满或金币不足直接回错误码。 */
    public void store(PacketSession session, PlayerCharacter character, int slot, int itemId, int quantity) {
        State state = active(session, character);
        if (state == null) return;
        if (slot < 1 || quantity < 1) return;
        InventoryType inventoryType = ItemConstants.getInventoryType(itemId);
        if (inventoryType == InventoryType.UNDEFINED) return;
        // v83 客户端不允许把现金道具放进仓库：cashId 为 0 的现金道具同样拒绝，否则解码会错。
        if (inventoryType == InventoryType.CASH) return;
        Map<Short, V83ItemSnapshot> bagBefore = GameplayPackets.inventory(character, inventoryType);
        V83ItemSnapshot slotItem = bagBefore.get((short) slot);
        if (slotItem == null || slotItem.itemId() != itemId) return;
        // 可充值道具（飞镖/子弹）必须整叠存入：客户端传来的数量不可信，否则会出现「只存 1 个却删整叠」。
        int requested = isRechargeable(itemId) ? slotItem.quantity() : Math.min(quantity, slotItem.quantity());
        TradeItemSnapshot snapshot = itemSystem.snapshotTradeItem(
                character, inventoryType.getType(), (short) slot, requested);
        if (snapshot == null) return;
        if (state.items.size() >= state.slotCount) {
            session.send(StoragePackets.error(StoragePackets.ERROR_STORAGE_FULL));
            return;
        }
        if (character.getMeso() < PUT_FEE) {
            session.send(StoragePackets.error(StoragePackets.ERROR_NO_MESO));
            return;
        }
        if (!itemSystem.takeTradeItems(character, List.of(snapshot))) return;
        if (PUT_FEE > 0) itemSystem.changeMeso(character, -PUT_FEE);
        Map<Short, V83ItemSnapshot> after = GameplayPackets.inventory(character, inventoryType);
        state.items.add(toEntity(snapshot, state.recordId, accountIdOf(character), nextSlotIndex(state, inventoryType)));
        renumber(state);
        persist(accountIdOf(character), state);
        session.send(StoragePackets.store(state.slotCount, typeBitfield(inventoryType),
                snapshotsOf(state, inventoryType), inventoryType.getType()));
        for (OutPacket packet : GameplayPackets.inventoryChanges(inventoryType, bagBefore, after)) {
            session.send(packet);
        }
    }

    /** 整理：先合并可叠加的同类物品，再按类型重排顺序下标；装备与宠物不合并。 */
    public void arrange(PacketSession session, PlayerCharacter character) {
        State state = active(session, character);
        if (state == null) return;
        merge(state);
        renumber(state);
        persist(accountIdOf(character), state);
        session.send(StoragePackets.arrange(state.slotCount, snapshotsOf(state)));
    }

    /**
     * 合并可叠加物品：同类型、同 itemId、同所有者/标记/期限，且总量不超过 slotMax 时并成一行。
     *
     * <p>放不下的部分保持独立行，不做拆分——拆分需要再造实例，收益低且容易与客户端下标错位。
     */
    private void merge(State state) {
        Map<String, StorageItemEntity> byKey = new HashMap<>();
        List<StorageItemEntity> result = new ArrayList<>();
        for (StorageItemEntity item : state.items) {
            if (!mergeable(item)) {
                result.add(item);
                continue;
            }
            String key = mergeKey(item);
            StorageItemEntity target = byKey.get(key);
            if (target == null) {
                byKey.put(key, item);
                result.add(item);
                continue;
            }
            int total = target.getQuantity() + item.getQuantity();
            if (total <= slotMaxOf(item.getItemId())) {
                target.setQuantity(total);
            } else {
                result.add(item);
            }
        }
        if (result.size() != state.items.size()) {
            state.items.clear();
            state.items.addAll(result);
        }
    }

    /** 只有普通堆叠物可合并；装备与宠物带着独立实例属性，永远各自一行。 */
    private static boolean mergeable(StorageItemEntity item) {
        return item.getInventoryType() != InventoryType.EQUIP.getType() && item.getPetId() <= 0;
    }

    private static String mergeKey(StorageItemEntity item) {
        return item.getInventoryType() + ":" + item.getItemId() + ":" + item.getOwner()
                + ":" + item.getFlag() + ":" + item.getExpiration();
    }

    private int slotMaxOf(int itemId) {
        if (data != null) {
            ItemData itemData = data.item(itemId);
            if (itemData != null && itemData.getSlotMax() > 0) return itemData.getSlotMax();
        }
        return ItemSystem.DEFAULT_SLOT_MAX;
    }

    /** 金币搬运：正数存入、负数取出，双向都做溢出与余额校验。 */
    public void meso(PacketSession session, PlayerCharacter character, int amount) {
        State state = active(session, character);
        if (state == null || amount == 0) return;
        long nextStorageMeso = (long) state.meso + amount;
        long nextCharacterMeso = (long) character.getMeso() - amount;
        if (nextStorageMeso < 0 || nextStorageMeso > Integer.MAX_VALUE
                || nextCharacterMeso < 0 || nextCharacterMeso > Integer.MAX_VALUE) {
            session.send(StoragePackets.error(StoragePackets.ERROR_NO_MESO));
            return;
        }
        if (!itemSystem.changeMeso(character, -amount)) {
            session.send(StoragePackets.error(StoragePackets.ERROR_NO_MESO));
            return;
        }
        state.meso = (int) nextStorageMeso;
        persist(accountIdOf(character), state);
        session.send(StoragePackets.meso(state.slotCount, state.meso));
    }

    /** 关闭仓库：清除会话属性，缓存保留给同账号下一个角色复用。 */
    public void close(PacketSession session, PlayerCharacter character) {
        session.setAttr(SESSION_ATTR, null);
    }

    /** 换图后仓库会话失效，避免在别的 NPC 处继续操作。 */
    public void onMapChange(PacketSession session) {
        session.setAttr(SESSION_ATTR, null);
    }

    /** 断线：清除会话属性；内存态仍会在下一次变更时落库，不在此同步等待写库。 */
    public void onDisconnect(PacketSession session) {
        session.setAttr(SESSION_ATTR, null);
    }

    /** 当前会话是否持有已打开的仓库；没有时静默忽略该操作。 */
    private State active(PacketSession session, PlayerCharacter character) {
        if (session.getAttr(SESSION_ATTR) == null) return null;
        long accountId = accountIdOf(character);
        if (accountId <= 0) return null;
        return cache.get(accountId);
    }

    /** 后台读库：仓库头与物品行一起加载，行不存在时按默认格数建一行。 */
    private State load(long accountId) {
        Optional<StorageRecordEntity> head = records.findByAccount((int) accountId, worldId);
        StorageRecordEntity record = head.orElseGet(
                () -> records.createIfAbsent((int) accountId, worldId, DEFAULT_SLOTS));
        if (record == null) {
            log.warn(I18n.message("log.storage.load_failed"), accountId);
            return null;
        }
        int slotCount = record.getSlotCount() <= 0 ? DEFAULT_SLOTS : Math.min(record.getSlotCount(), MAX_SLOTS);
        State state = new State(record.getStorageRecordId(), slotCount, record.getRowVersion(), record.getMeso());
        state.items.addAll(items.findByRecord(record.getStorageRecordId()));
        renumber(state);
        return state;
    }

    /** 落库：先在频道线程取不可变快照，再交后台覆盖写入；版本冲突时丢弃缓存让下次重读库。 */
    private void persist(long accountId, State state) {
        List<StorageItemEntity> snapshot = new ArrayList<>();
        for (StorageItemEntity item : state.items) {
            snapshot.add(copyOf(item));
        }
        int expectedVersion = state.version;
        int meso = state.meso;
        int slotCount = state.slotCount;
        long recordId = state.recordId;
        state.version = expectedVersion + 1;
        if (io == null) {
            // 测试或无频道适配器时同步落库，与 offload 的兜底语义保持一致。
            if (applyPersist(accountId, recordId, meso, slotCount, expectedVersion, snapshot)) {
                cache.remove(accountId);
            }
            return;
        }
        io.submit(() -> applyPersist(accountId, recordId, meso, slotCount, expectedVersion, snapshot))
                .whenComplete((conflict, error) -> {
                    if (error != null) {
                        log.error(I18n.message("log.storage.persist_failed"), accountId, error);
                        return;
                    }
                    if (Boolean.TRUE.equals(conflict) && sessions.execution() != null) {
                        sessions.execution().execute(() -> cache.remove(accountId));
                    }
                });
    }

    /** 覆盖写仓库头与物品行；返回 {@code true} 表示版本冲突，需要丢弃内存缓存。 */
    private boolean applyPersist(long accountId, long recordId, int meso, int slotCount,
                                 int expectedVersion, List<StorageItemEntity> snapshot) {
        boolean updated = records.updateMesoAndSlots(recordId, meso, slotCount, expectedVersion);
        if (!updated) {
            // 另一个频道或进程已经写过：本次内存态仍然生效，但要让下一次读取重新对齐数据库版本。
            log.warn(I18n.message("log.storage.version_conflict"), accountId, expectedVersion);
            return true;
        }
        items.replaceAll(recordId, (int) accountId, snapshot);
        return false;
    }

    private void sendBagChanges(PacketSession session, PlayerCharacter character, InventoryType type,
                                Map<Short, V83ItemSnapshot> before) {
        Map<Short, V83ItemSnapshot> after = GameplayPackets.inventory(character, type);
        for (OutPacket packet : GameplayPackets.inventoryChanges(type, before, after)) {
            session.send(packet);
        }
    }

    /** 该类型在仓库中的物品，按顺序下标升序；客户端下标就是这个列表的下标。 */
    private static List<StorageItemEntity> typedItems(State state, InventoryType type) {
        List<StorageItemEntity> typed = new ArrayList<>();
        for (StorageItemEntity item : state.items) {
            if (item.getInventoryType() == type.getType()) typed.add(item);
        }
        typed.sort(Comparator.comparingInt(StorageItemEntity::getSlotIndex));
        return typed;
    }

    private static int nextSlotIndex(State state, InventoryType type) {
        int max = -1;
        for (StorageItemEntity item : state.items) {
            if (item.getInventoryType() == type.getType()) max = Math.max(max, item.getSlotIndex());
        }
        return max + 1;
    }

    /** 任何增删后按 (类型, 原下标) 重新编号，保证每类型从 0 连续。 */
    private static void renumber(State state) {
        state.items.sort(Comparator.comparingInt(StorageItemEntity::getInventoryType)
                .thenComparingInt(StorageItemEntity::getSlotIndex));
        Map<Integer, Integer> nextByType = new HashMap<>();
        for (StorageItemEntity item : state.items) {
            int next = nextByType.getOrDefault(item.getInventoryType(), 0);
            item.setSlotIndex(next);
            nextByType.put(item.getInventoryType(), next + 1);
        }
    }

    private static List<V83ItemSnapshot> snapshotsOf(State state) {
        List<V83ItemSnapshot> result = new ArrayList<>();
        for (StorageItemEntity item : state.items) {
            V83ItemSnapshot snapshot = toV83(item);
            if (snapshot != null) result.add(snapshot);
        }
        return result;
    }

    private static List<V83ItemSnapshot> snapshotsOf(State state, InventoryType type) {
        List<V83ItemSnapshot> result = new ArrayList<>();
        for (StorageItemEntity item : typedItems(state, type)) {
            V83ItemSnapshot snapshot = toV83(item);
            if (snapshot != null) result.add(snapshot);
        }
        return result;
    }

    private static int typeBitfield(InventoryType type) {
        return 2 << type.getType();
    }

    /** 飞镖/子弹等可充值道具：WZ 分类 207xx / 233xx，仓库按整叠处理。 */
    private static boolean isRechargeable(int itemId) {
        int category = itemId / 10000;
        return category == 207 || category == 233;
    }

    private static V83ItemSnapshot toV83(StorageItemEntity entity) {
        int itemType = entity.getInventoryType() == InventoryType.EQUIP.getType() ? 1 : 2;
        long protocolCashId = entity.getCashId();
        V83EquipStats equip = null;
        V83PetStats pet = null;
        if (itemType == 2 && entity.getPetId() <= 0 && entity.getCashId() <= 0
                && entity.getInventoryType() == InventoryType.CASH.getType()) {
            // 现金道具必须带 cashId 才能编码；坏数据直接跳过，否则整个物品段会错位。
            return null;
        }
        if (itemType == 1) {
            equip = new V83EquipStats(entity.getUpgradeSlots(), entity.getLevel(), entity.getStrStat(),
                    entity.getDexStat(), entity.getIntStat(), entity.getLukStat(), entity.getHp(), entity.getMp(),
                    entity.getWAtk(), entity.getMAtk(), entity.getWDef(), entity.getMDef(), entity.getAcc(),
                    entity.getAvoid(), entity.getHands(), entity.getSpeed(), entity.getJump(), entity.getVicious(),
                    entity.getItemLevel(), entity.getItemExp());
        } else if (entity.getPetId() > 0) {
            pet = new V83PetStats(entity.getPetName(), entity.getPetLevel(), entity.getPetCloseness(),
                    entity.getPetFullness(), entity.getPetAttribute(), entity.getPetSkill(),
                    entity.getPetRemainLife(), entity.getItemAttribute());
            protocolCashId = entity.getPetId();
        }
        return new V83ItemSnapshot(entity.getSlotIndex(), itemType, entity.getItemId(), protocolCashId,
                entity.getExpiration(), entity.getQuantity(), entity.getOwner(), entity.getFlag(), equip, pet);
    }

    private static TradeItemSnapshot toSnapshot(StorageItemEntity entity) {
        TradeItemSnapshot.EquipSnapshot equip = null;
        TradeItemSnapshot.PetSnapshot pet = null;
        if (entity.getInventoryType() == InventoryType.EQUIP.getType()) {
            equip = new TradeItemSnapshot.EquipSnapshot((byte) entity.getUpgradeSlots(), (short) entity.getLevel(),
                    (short) entity.getStrStat(), (short) entity.getDexStat(), (short) entity.getIntStat(),
                    (short) entity.getLukStat(), (short) entity.getHp(), (short) entity.getMp(),
                    (short) entity.getWAtk(), (short) entity.getMAtk(), (short) entity.getWDef(),
                    (short) entity.getMDef(), (short) entity.getAcc(), (short) entity.getAvoid(),
                    (short) entity.getHands(), (short) entity.getSpeed(), (short) entity.getJump(),
                    (byte) entity.getVicious(), (byte) entity.getItemLevel(), entity.getItemExp(),
                    entity.getRingId());
        } else if (entity.getPetId() > 0) {
            pet = new TradeItemSnapshot.PetSnapshot(entity.getPetName(), (byte) entity.getPetLevel(),
                    (short) entity.getPetCloseness(), (byte) entity.getPetFullness(),
                    (short) entity.getPetAttribute(), (short) entity.getPetSkill(),
                    entity.getPetRemainLife(), (short) entity.getItemAttribute());
        }
        return new TradeItemSnapshot((byte) entity.getInventoryType(), (short) entity.getSlotIndex(),
                entity.getItemId(), entity.getQuantity(), entity.getCashId(), entity.getPetId(),
                entity.getOwner(), entity.getFlag(), entity.getExpiration(), entity.getGiftFrom(), equip, pet);
    }

    private static StorageItemEntity toEntity(TradeItemSnapshot snapshot, long recordId, long accountId, int slotIndex) {
        StorageItemEntity entity = new StorageItemEntity();
        entity.setStorageRecordId(recordId);
        entity.setAccountId((int) accountId);
        entity.setInventoryType(snapshot.inventoryType());
        entity.setSlotIndex(slotIndex);
        entity.setItemId(snapshot.itemId());
        entity.setQuantity(snapshot.quantity());
        entity.setOwner(snapshot.owner());
        entity.setCashId(snapshot.cashId());
        entity.setPetId(snapshot.petId());
        entity.setFlag(snapshot.flag());
        entity.setExpiration(snapshot.expiration());
        entity.setGiftFrom(snapshot.giftFrom());
        TradeItemSnapshot.EquipSnapshot equip = snapshot.equip();
        if (equip != null) {
            entity.setUpgradeSlots(equip.upgradeSlots());
            entity.setLevel(equip.level());
            entity.setStrStat(equip.strStat());
            entity.setDexStat(equip.dexStat());
            entity.setIntStat(equip.intStat());
            entity.setLukStat(equip.lukStat());
            entity.setHp(equip.hp());
            entity.setMp(equip.mp());
            entity.setWAtk(equip.wAtk());
            entity.setMAtk(equip.mAtk());
            entity.setWDef(equip.wDef());
            entity.setMDef(equip.mDef());
            entity.setAcc(equip.acc());
            entity.setAvoid(equip.avoid());
            entity.setHands(equip.hands());
            entity.setSpeed(equip.speed());
            entity.setJump(equip.jump());
            entity.setVicious(equip.vicious());
            entity.setItemLevel(equip.itemLevel());
            entity.setItemExp(equip.itemExp());
            entity.setRingId(equip.ringId());
        }
        TradeItemSnapshot.PetSnapshot pet = snapshot.pet();
        if (pet != null) {
            entity.setPetName(pet.name());
            entity.setPetLevel(pet.level());
            entity.setPetCloseness(pet.closeness());
            entity.setPetFullness(pet.fullness());
            entity.setPetAttribute(pet.attribute());
            entity.setPetSkill(pet.skill());
            entity.setPetRemainLife(pet.remainLife());
            entity.setItemAttribute(pet.itemAttribute());
        }
        return entity;
    }

    private static StorageItemEntity copyOf(StorageItemEntity source) {
        StorageItemEntity copy = new StorageItemEntity();
        copy.setStorageRecordId(source.getStorageRecordId());
        copy.setAccountId(source.getAccountId());
        copy.setInventoryType(source.getInventoryType());
        copy.setSlotIndex(source.getSlotIndex());
        copy.setItemId(source.getItemId());
        copy.setQuantity(source.getQuantity());
        copy.setOwner(source.getOwner());
        copy.setCashId(source.getCashId());
        copy.setPetId(source.getPetId());
        copy.setFlag(source.getFlag());
        copy.setExpiration(source.getExpiration());
        copy.setGiftFrom(source.getGiftFrom());
        copy.setUpgradeSlots(source.getUpgradeSlots());
        copy.setLevel(source.getLevel());
        copy.setStrStat(source.getStrStat());
        copy.setDexStat(source.getDexStat());
        copy.setIntStat(source.getIntStat());
        copy.setLukStat(source.getLukStat());
        copy.setHp(source.getHp());
        copy.setMp(source.getMp());
        copy.setWAtk(source.getWAtk());
        copy.setMAtk(source.getMAtk());
        copy.setWDef(source.getWDef());
        copy.setMDef(source.getMDef());
        copy.setAcc(source.getAcc());
        copy.setAvoid(source.getAvoid());
        copy.setHands(source.getHands());
        copy.setSpeed(source.getSpeed());
        copy.setJump(source.getJump());
        copy.setVicious(source.getVicious());
        copy.setItemLevel(source.getItemLevel());
        copy.setItemExp(source.getItemExp());
        copy.setRingId(source.getRingId());
        copy.setPetName(source.getPetName());
        copy.setPetLevel(source.getPetLevel());
        copy.setPetCloseness(source.getPetCloseness());
        copy.setPetFullness(source.getPetFullness());
        copy.setPetAttribute(source.getPetAttribute());
        copy.setPetSkill(source.getPetSkill());
        copy.setPetRemainLife(source.getPetRemainLife());
        copy.setItemAttribute(source.getItemAttribute());
        return copy;
    }

    private static long accountIdOf(PlayerCharacter character) {
        Long accountId = character.getAccountId();
        return accountId == null ? 0L : accountId;
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
}
