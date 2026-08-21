package org.gms.domain.game;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.inventory.PetItem;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.TradeItemSnapshot;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 角色（纯数据，稳定层，内存态权威）。字段对齐 characters 表 74 列（红线 3 存档格式兼容），
 * 附加内存态（背包/技能/逻辑版本）。Lombok 生成字段 getter/setter（红线 11）。
 *
 * <p><b>状态/逻辑分离</b>：本类只持有状态，不含业务逻辑——移动/战斗/交易等一律在可替换层
 * 逻辑系统（M2-2），经接口访问本类状态。手动 new、不进容器（红线 4）。
 *
 * <p><b>热重载安全</b>：实现 {@link CharacterState}（稳定层 SPI，逻辑系统经它访问），
 * 构造时携带创建它的逻辑版本（来自 {@link org.gms.hotreload.versioned.VersionGate#currentVersion()}）。
 * 重载换代后，迟到的旧逻辑写操作经版本门识别（架构 5.3）。
 *
 * <p>新角色默认值对齐 v83（思路参考自 BeiDou-Server 的 Character.getDefault）。
 */
@Getter
@Setter
public class Character implements CharacterState {

    // ---------- 持久化字段（characters 表 74 列，红线 3） ----------

    private long id;
    private Long accountId;
    private int world;
    private String name;
    private int level;
    private long exp;
    private long gachaExp;
    private short strStat;
    private short dexStat;
    private short lukStat;
    private short intStat;
    private int hp;
    private int mp;
    private int maxHp;
    private int maxMp;
    private int meso;
    private int hpMpUsed;
    private int job;
    private int skinColor;
    private int gender;
    private int fame;
    private int fquest;
    private int hair;
    private int face;
    private int ap;
    private String sp;
    private int map;
    private int spawnPoint;
    private int gm;
    private int party;
    private int buddyCapacity;
    private String createDate;
    private long rank;
    private int rankMove;
    private long jobRank;
    private int jobRankMove;
    private int guildId;
    private int guildRank;
    private int messengerId;
    private int messengerPosition;
    private int mountLevel;
    private int mountExp;
    private int mountTiredness;
    private int omokWins;
    private int omokLosses;
    private int omokTies;
    private int matchCardWins;
    private int matchCardLosses;
    private int matchCardTies;
    private int merchantMesos;
    private boolean hasMerchant;
    private int equipSlots;
    private int useSlots;
    private int setupSlots;
    private int etcSlots;
    private int familyId;
    private int monsterBookCover;
    private int allianceRank;
    private int vanquisherStage;
    private int ariantPoints;
    private int dojoPoints;
    private int lastDojoStage;
    private boolean finishedDojoTutorial;
    private int vanquisherKills;
    private int summonValue;
    private int partnerId;
    private int marriageItemId;
    private int reborns;
    private int pqPoints;
    private String dataString;
    private String lastLogoutTime;
    private String lastExpGainTime;
    private boolean partySearch;
    private long jailExpire;

    // ---------- 内存态字段（非持久化，Lombok 抑制 getter/setter，用自定义方法） ----------

    /** 现金背包槽位上限（v83 固定）。 */
    private static final int CASH_SLOT_LIMIT = 100;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final EnumMap<InventoryType, Inventory> inventories = new EnumMap<>(InventoryType.class);

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<Integer, SkillEntry> skills = new HashMap<>();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<Integer, QuestStatus> quests = new HashMap<>();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final long logicVersion;

    /** 地图内坐标（运行时，非持久化；地图 id 见 {@code map} 持久化字段）。 */
    private int x;
    private int y;

    /** 当前所在地图对象（运行时，非持久化；进图时由频道装配，换图时更新）。 */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private org.gms.domain.game.map.MapleMap mapObject;

    /** 自上次落盘后是否有变更（L4 增量 FLUSH 用，红线 17：只 FLUSH 脏数据）。 */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient volatile boolean dirty;

    /** 每次持久化状态变更递增；存档线程据此避免清除较新的脏状态。 */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient long dirtyVersion;

    public org.gms.domain.game.map.MapleMap getMapObject() {
        return mapObject;
    }

    public void setMapObject(org.gms.domain.game.map.MapleMap mapObject) {
        this.mapObject = mapObject;
    }

    /** 标记自上次落盘后已变更（持久化字段 setter / 背包/任务 mutation 内调用）。 */
    @Override
    public synchronized void markDirty() {
        this.dirtyVersion++;
        this.dirty = true;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public synchronized void clearDirty() {
        this.dirty = false;
    }

    /** 返回当前持久化状态版本，供异步存档建立确认点。 */
    public synchronized long dirtyVersion() {
        return dirtyVersion;
    }

    /** 仅当存档期间没有新变更时清脏。 */
    public synchronized boolean clearDirty(long savedVersion) {
        if (dirtyVersion != savedVersion) {
            return false;
        }
        dirty = false;
        return true;
    }

    // ---- 持久化字段手写 setter（覆盖 Lombok 生成，赋值后标脏，红线 17 增量 FLUSH 依据） ----
    // 手写同名方法后 Lombok 不再生成该字段 setter；坐标 x/y 不标脏（运行期不落库，map 已持久化）。

    public void setLevel(int level) {
        this.level = level;
        markDirty();
    }

    public void setExp(long exp) {
        this.exp = exp;
        markDirty();
    }

    public void setHp(int hp) {
        this.hp = hp;
        markDirty();
    }

    public void setMp(int mp) {
        this.mp = mp;
        markDirty();
    }

    public void setMaxHp(int maxHp) {
        this.maxHp = maxHp;
        markDirty();
    }

    public void setMaxMp(int maxMp) {
        this.maxMp = maxMp;
        markDirty();
    }

    public synchronized int getMeso() {
        return meso;
    }

    public synchronized void setMeso(int meso) {
        this.meso = meso;
        markDirty();
    }

    public void setJob(int job) {
        this.job = job;
        markDirty();
    }

    public void setMap(int map) {
        this.map = map;
        markDirty();
    }

    /**
     * @param logicVersion 创建该角色的逻辑版本（来自 VersionGate.currentVersion()）
     */
    public Character(long logicVersion) {
        this.logicVersion = logicVersion;
        // v83 新角色默认值
        this.level = 1;
        this.exp = 0;
        this.strStat = 12;
        this.dexStat = 5;
        this.lukStat = 4;
        this.intStat = 4;
        this.hp = 50;
        this.mp = 5;
        this.maxHp = 50;
        this.maxMp = 5;
        this.job = 0;
        this.sp = "0,0,0,0,0,0,0,0,0,0";
        this.gm = 0;
        this.buddyCapacity = 25;
        this.rank = 1;
        this.jobRank = 1;
        this.guildRank = 5;
        this.messengerPosition = 4;
        this.mountLevel = 1;
        this.equipSlots = 24;
        this.useSlots = 24;
        this.setupSlots = 24;
        this.etcSlots = 24;
        this.familyId = -1;
        this.allianceRank = 5;
        this.partySearch = true;
    }

    // ---------- 内存态操作（纯数据集合，自定义方法） ----------

    /**
     * 取某类型背包（懒创建，槽位上限取当前持久化槽位字段）。
     * 加载角色时须先 set 各 slot 字段再访问背包，槽位上限才正确。
     */
    public Inventory getInventory(InventoryType type) {
        return inventories.computeIfAbsent(type, t -> new Inventory(t, slotLimitFor(t)));
    }

    private int slotLimitFor(InventoryType type) {
        return switch (type) {
            case EQUIP -> equipSlots;
            case USE -> useSlots;
            case SETUP -> setupSlots;
            case ETC -> etcSlots;
            case CASH -> CASH_SLOT_LIMIT;
            default -> 0;
        };
    }

    public SkillEntry getSkill(int skillId) {
        return skills.get(skillId);
    }

    public void putSkill(SkillEntry entry) {
        skills.put(entry.skillId(), entry);
        markDirty();
    }

    public void removeSkill(int skillId) {
        skills.remove(skillId);
        markDirty();
    }

    /** 全部技能（不可变视图）。 */
    public Map<Integer, SkillEntry> skills() {
        return Map.copyOf(skills);
    }

    /** 加载存档时放入完整任务状态。 */
    public void putQuest(QuestStatus status) {
        quests.put(status.getQuestId(), status);
        markDirty();
    }

    /** 全部任务状态（不可变视图）。 */
    public Map<Integer, QuestStatus> quests() {
        return Map.copyOf(quests);
    }

    @Override
    public synchronized boolean addItem(int itemId, int quantity, int slotMax) {
        if (quantity <= 0 || slotMax <= 0) {
            return false;
        }
        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (type == InventoryType.UNDEFINED) {
            return false;
        }
        Inventory inv = getInventory(type);
        // 容量预检：未满槽剩余 + 空槽 × slotMax；不足不动（无副作用）
        int capacity = 0;
        for (Item existing : inv.items()) {
            if (existing.getId() == itemId) {
                capacity += Math.max(0, slotMax - existing.getQuantity());
            }
        }
        capacity += inv.freeSlots() * slotMax;
        if (capacity < quantity) {
            return false;
        }
        // 先堆叠未满槽，再分配空槽
        int remaining = quantity;
        for (Item existing : inv.items()) {
            if (existing.getId() != itemId) {
                continue;
            }
            int space = slotMax - existing.getQuantity();
            if (space <= 0) {
                continue;
            }
            int add = Math.min(space, remaining);
            existing.setQuantity((short) (existing.getQuantity() + add));
            remaining -= add;
            if (remaining == 0) {
                markDirty();
                return true;
            }
        }
        while (remaining > 0) {
            Item item = new Item(itemId);
            int put = Math.min(slotMax, remaining);
            item.setQuantity((short) put);
            inv.addItem(item);
            remaining -= put;
        }
        markDirty();
        return true;
    }

    @Override
    public synchronized boolean canAddItems(Map<Integer, Integer> quantities,
                                            Map<Integer, Integer> slotMaxByItem) {
        EnumMap<InventoryType, Integer> requiredSlots = new EnumMap<>(InventoryType.class);
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            int itemId = entry.getKey();
            int quantity = entry.getValue();
            int slotMax = slotMaxByItem.getOrDefault(itemId, 0);
            InventoryType type = ItemConstants.getInventoryType(itemId);
            if (quantity <= 0 || slotMax <= 0 || type == InventoryType.UNDEFINED) {
                return false;
            }
            long stackCapacity = 0;
            Inventory inventory = getInventory(type);
            for (Item existing : inventory.items()) {
                if (existing.getId() == itemId) {
                    stackCapacity += Math.max(0, slotMax - existing.getQuantity());
                }
            }
            long remaining = Math.max(0L, (long) quantity - stackCapacity);
            int slots = (int) ((remaining + slotMax - 1L) / slotMax);
            requiredSlots.merge(type, slots, Integer::sum);
        }
        for (Map.Entry<InventoryType, Integer> entry : requiredSlots.entrySet()) {
            if (entry.getValue() > getInventory(entry.getKey()).freeSlots()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized int getItemCount(int itemId) {
        int count = 0;
        for (InventoryType type : InventoryType.values()) {
            Inventory inv = inventories.get(type);
            if (inv == null) {
                continue;
            }
            for (Item item : inv.items()) {
                if (item.getId() == itemId) {
                    count += item.getQuantity();
                }
            }
        }
        return count;
    }

    @Override
    public synchronized boolean removeItem(int itemId, int quantity) {
        if (quantity <= 0) {
            return false;
        }
        int remaining = quantity;
        for (InventoryType type : InventoryType.values()) {
            Inventory inv = inventories.get(type);
            if (inv == null) {
                continue;
            }
            for (Item item : List.copyOf(inv.items())) {
                if (item.getId() != itemId) {
                    continue;
                }
                int take = Math.min(item.getQuantity(), remaining);
                item.setQuantity((short) (item.getQuantity() - take));
                remaining -= take;
                if (item.getQuantity() == 0) {
                    inv.removeItem(item.getPosition());
                }
                if (remaining == 0) {
                    markDirty();
                    return true;
                }
            }
        }
        if (remaining == 0) {
            markDirty();
        }
        return remaining == 0;
    }

    @Override
    public synchronized TradeItemSnapshot snapshotTradeItem(byte inventoryType,
                                                            short sourcePosition,
                                                            int quantity) {
        InventoryType type = InventoryType.getByType(inventoryType);
        if (type == InventoryType.UNDEFINED || sourcePosition <= 0 || quantity <= 0) {
            return null;
        }
        Item item = getInventory(type).getItem(sourcePosition);
        if (item == null || quantity > item.getQuantity()
                || ItemConstants.getInventoryType(item.getId()) != type
                || item instanceof Equip && quantity != 1) {
            return null;
        }
        return toTradeSnapshot(type, item, quantity);
    }

    @Override
    public synchronized boolean hasTradeItems(List<TradeItemSnapshot> items) {
        Set<Long> sources = new HashSet<>();
        for (TradeItemSnapshot offered : items) {
            if (offered == null || !sources.add(sourceKey(offered))) {
                return false;
            }
            TradeItemSnapshot current = snapshotTradeItem(
                    offered.inventoryType(), offered.sourcePosition(), offered.quantity());
            if (!offered.equals(current)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized boolean canExchangeTradeItems(List<TradeItemSnapshot> outgoing,
                                                      List<TradeItemSnapshot> incoming,
                                                      Map<Integer, Integer> slotMaxByItem) {
        EnumMap<InventoryType, Inventory> simulated = copyInventories();
        return removeTradeItems(simulated, outgoing)
                && addTradeItems(simulated, incoming, slotMaxByItem);
    }

    @Override
    public synchronized boolean removeTradeItems(List<TradeItemSnapshot> items) {
        if (!hasTradeItems(items) || !removeTradeItems(inventories, items)) {
            return false;
        }
        if (!items.isEmpty()) {
            markDirty();
        }
        return true;
    }

    @Override
    public synchronized boolean addTradeItems(List<TradeItemSnapshot> items,
                                              Map<Integer, Integer> slotMaxByItem) {
        for (TradeItemSnapshot item : items) {
            if (item != null) {
                InventoryType type = InventoryType.getByType(item.inventoryType());
                if (type != InventoryType.UNDEFINED) {
                    getInventory(type);
                }
            }
        }
        EnumMap<InventoryType, Inventory> simulated = copyInventories();
        if (!addTradeItems(simulated, items, slotMaxByItem)
                || !addTradeItems(inventories, items, slotMaxByItem)) {
            return false;
        }
        if (!items.isEmpty()) {
            markDirty();
        }
        return true;
    }

    private EnumMap<InventoryType, Inventory> copyInventories() {
        EnumMap<InventoryType, Inventory> copy = new EnumMap<>(InventoryType.class);
        for (InventoryType type : InventoryType.values()) {
            if (type == InventoryType.UNDEFINED) {
                continue;
            }
            Inventory source = inventories.get(type);
            Inventory target = new Inventory(type, slotLimitFor(type));
            if (source != null) {
                for (Item item : source.items()) {
                    Item cloned = item.copy();
                    target.putAtSlot(cloned.getPosition(), cloned);
                }
            }
            copy.put(type, target);
        }
        return copy;
    }

    private static boolean removeTradeItems(EnumMap<InventoryType, Inventory> target,
                                            List<TradeItemSnapshot> items) {
        Set<Long> sources = new HashSet<>();
        for (TradeItemSnapshot offered : items) {
            if (offered == null || !sources.add(sourceKey(offered))) {
                return false;
            }
            InventoryType type = InventoryType.getByType(offered.inventoryType());
            Inventory inventory = target.get(type);
            Item current = inventory == null ? null : inventory.getItem(offered.sourcePosition());
            if (current == null || !offered.equals(toTradeSnapshot(type, current, offered.quantity()))) {
                return false;
            }
        }
        for (TradeItemSnapshot offered : items) {
            Inventory inventory = target.get(InventoryType.getByType(offered.inventoryType()));
            Item current = inventory.getItem(offered.sourcePosition());
            if (current.getQuantity() == offered.quantity()) {
                inventory.removeItem(offered.sourcePosition());
            } else {
                current.setQuantity((short) (current.getQuantity() - offered.quantity()));
            }
        }
        return true;
    }

    private static boolean addTradeItems(EnumMap<InventoryType, Inventory> target,
                                         List<TradeItemSnapshot> items,
                                         Map<Integer, Integer> slotMaxByItem) {
        for (TradeItemSnapshot offered : items) {
            if (offered == null || offered.quantity() <= 0) {
                return false;
            }
            InventoryType type = InventoryType.getByType(offered.inventoryType());
            int slotMax = slotMaxByItem.getOrDefault(offered.itemId(), 0);
            if (type == InventoryType.UNDEFINED || slotMax <= 0
                    || ItemConstants.getInventoryType(offered.itemId()) != type
                    || (offered.equip() != null || offered.pet() != null)
                    && (offered.quantity() != 1 || slotMax != 1)) {
                return false;
            }
            Inventory inventory = target.get(type);
            if (inventory == null) {
                return false;
            }
            int remaining = offered.quantity();
            Item template = fromTradeSnapshot(offered);
            if (offered.equip() == null && offered.pet() == null) {
                for (Item existing : inventory.items()) {
                    if (!stackCompatible(existing, template)) {
                        continue;
                    }
                    int space = Math.max(0, slotMax - existing.getQuantity());
                    int added = Math.min(space, remaining);
                    if (added > 0) {
                        existing.setQuantity((short) (existing.getQuantity() + added));
                        remaining -= added;
                    }
                    if (remaining == 0) {
                        break;
                    }
                }
            }
            while (remaining > 0) {
                Item added = fromTradeSnapshot(offered);
                int quantity = Math.min(slotMax, remaining);
                added.setQuantity((short) quantity);
                if (!inventory.addItem(added)) {
                    return false;
                }
                remaining -= quantity;
            }
        }
        return true;
    }

    private static boolean stackCompatible(Item existing, Item incoming) {
        return existing.getClass() == incoming.getClass()
                && existing.getId() == incoming.getId()
                && existing.getCashId() == incoming.getCashId()
                && existing.getPetId() == incoming.getPetId()
                && Objects.equals(existing.getOwner(), incoming.getOwner())
                && existing.getFlag() == incoming.getFlag()
                && existing.getExpiration() == incoming.getExpiration()
                && Objects.equals(existing.getGiftFrom(), incoming.getGiftFrom());
    }

    private static long sourceKey(TradeItemSnapshot item) {
        return (((long) item.inventoryType() & 0xffL) << 32)
                | ((long) item.sourcePosition() & 0xffffL);
    }

    private static TradeItemSnapshot toTradeSnapshot(InventoryType type, Item item, int quantity) {
        if (type == null || type == InventoryType.UNDEFINED || item == null
                || quantity <= 0 || quantity > item.getQuantity()) {
            return null;
        }
        TradeItemSnapshot.EquipSnapshot equip = null;
        TradeItemSnapshot.PetSnapshot pet = null;
        if (item instanceof Equip value) {
            equip = new TradeItemSnapshot.EquipSnapshot(
                    value.getUpgradeSlots(), value.getLevel(), value.getStrStat(), value.getDexStat(),
                    value.getIntStat(), value.getLukStat(), value.getHp(), value.getMp(), value.getWAtk(),
                    value.getMAtk(), value.getWDef(), value.getMDef(), value.getAcc(), value.getAvoid(),
                    value.getHands(), value.getSpeed(), value.getJump(), value.getVicious(),
                    value.getItemLevel(), value.getItemExp(), value.getRingId());
        } else if (item instanceof PetItem value) {
            pet = new TradeItemSnapshot.PetSnapshot(
                    value.getPetName(), value.getPetLevel(), value.getCloseness(), value.getFullness(),
                    value.getPetAttribute(), value.getPetSkill(), value.getRemainLife(), value.getAttribute());
        }
        return new TradeItemSnapshot(type.getType(), item.getPosition(), item.getId(), quantity,
                item.getCashId(), item.getPetId(), item.getOwner(), item.getFlag(), item.getExpiration(),
                item.getGiftFrom(), equip, pet);
    }

    private static Item fromTradeSnapshot(TradeItemSnapshot snapshot) {
        Item item;
        if (snapshot.pet() != null) {
            TradeItemSnapshot.PetSnapshot source = snapshot.pet();
            PetItem pet = new PetItem(snapshot.itemId(), snapshot.petId());
            pet.setPetName(source.name());
            pet.setPetLevel(source.level());
            pet.setCloseness(source.closeness());
            pet.setFullness(source.fullness());
            pet.setPetAttribute(source.attribute());
            pet.setPetSkill(source.skill());
            pet.setRemainLife(source.remainLife());
            pet.setAttribute(source.itemAttribute());
            item = pet;
        } else if (snapshot.equip() == null) {
            item = new Item(snapshot.itemId());
        } else {
            TradeItemSnapshot.EquipSnapshot source = snapshot.equip();
            Equip equip = new Equip(snapshot.itemId());
            equip.setUpgradeSlots(source.upgradeSlots());
            equip.setLevel(source.level());
            equip.setStrStat(source.strStat());
            equip.setDexStat(source.dexStat());
            equip.setIntStat(source.intStat());
            equip.setLukStat(source.lukStat());
            equip.setHp(source.hp());
            equip.setMp(source.mp());
            equip.setWAtk(source.wAtk());
            equip.setMAtk(source.mAtk());
            equip.setWDef(source.wDef());
            equip.setMDef(source.mDef());
            equip.setAcc(source.acc());
            equip.setAvoid(source.avoid());
            equip.setHands(source.hands());
            equip.setSpeed(source.speed());
            equip.setJump(source.jump());
            equip.setVicious(source.vicious());
            equip.setItemLevel(source.itemLevel());
            equip.setItemExp(source.itemExp());
            equip.setRingId(source.ringId());
            item = equip;
        }
        item.setCashId(snapshot.cashId());
        item.setPosition(snapshot.sourcePosition());
        item.setQuantity((short) snapshot.quantity());
        item.setPetId(snapshot.petId());
        item.setOwner(snapshot.owner());
        item.setFlag(snapshot.flag());
        item.setExpiration(snapshot.expiration());
        item.setGiftFrom(snapshot.giftFrom());
        return item;
    }

    @Override
    public QuestStatus getQuestStatus(int questId) {
        return quests.get(questId);
    }

    @Override
    public boolean startQuest(int questId) {
        QuestStatus existing = quests.get(questId);
        if (existing != null && existing.getState() == QuestStatus.State.COMPLETED) {
            return false;   // 已完成不能重开
        }
        QuestStatus qs = new QuestStatus(questId);
        qs.setState(QuestStatus.State.STARTED);
        quests.put(questId, qs);
        markDirty();
        return true;
    }

    @Override
    public boolean completeQuest(int questId) {
        QuestStatus qs = quests.get(questId);
        if (qs == null || qs.getState() != QuestStatus.State.STARTED) {
            return false;
        }
        qs.setState(QuestStatus.State.COMPLETED);
        qs.setCompletionTime(System.currentTimeMillis());
        qs.setCompleted(qs.getCompleted() + 1);
        markDirty();
        return true;
    }

    @Override
    public boolean setQuestProgress(int questId, int key, int value) {
        QuestStatus qs = quests.get(questId);
        if (qs == null || qs.getState() != QuestStatus.State.STARTED) {
            return false;
        }
        qs.setProgress(key, value);
        markDirty();
        return true;
    }

    @Override
    public long logicVersion() {
        return logicVersion;
    }
}
