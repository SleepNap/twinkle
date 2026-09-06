package org.gms.domain.game;

import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.concurrent.GameExecution;
import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.inventory.PetItem;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.skill.ActiveBuff;
import org.gms.domain.game.spi.BuffState;
import org.gms.domain.game.quest.QuestChange;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.AvatarState;
import org.gms.domain.game.spi.ControlsState;
import org.gms.domain.game.spi.EquipmentState;
import org.gms.domain.game.spi.EquipmentStats;
import org.gms.domain.game.control.ControlSettings;
import org.gms.domain.game.spi.TradeItemSnapshot;

import java.util.HashSet;
import java.util.HashMap;
import java.util.EnumMap;
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
 * 未绑定对象携带创建版本；在线对象的版本来自所属频道当前操作，延迟回调需显式携带版本。
 * 重载换代后，迟到的旧逻辑写操作经版本门识别（架构 5.3）。
 *
 * <p>新角色默认值对齐 v83（思路参考自 BeiDou-Server 的 PlayerCharacter.getDefault）。
 */
@Getter
@Setter
public class PlayerCharacter implements BuffState, AvatarState, ControlsState, EquipmentState {
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile GameExecution execution;

    public GameExecution execution() { return execution; }
    public void bindExecution(GameExecution owner) {
        owner.requireOwner();
        if (execution != null && execution != owner) throw new IllegalStateException(I18n.message("error.execution.character_owner"));
        execution = owner;
        questBook.snapshot().values().forEach(status -> status.bindExecution(owner));
        for (InventoryType type : InventoryType.values()) {
            if (type != InventoryType.UNDEFINED) getInventory(type).bindExecution(owner);
        }
    }
    private void requireStateAccess() { if (execution != null) execution.requireOwner(); }

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile EquipmentStats equipmentStats = EquipmentStats.EMPTY;

    @Override public EquipmentStats equipmentStats() { return equipmentStats; }
    @Override public void setEquipmentStats(EquipmentStats stats) { requireStateAccess(); equipmentStats = Objects.requireNonNull(stats); }
    @Override public int equipmentSlotLimit() { return getInventory(InventoryType.EQUIP).getSlotLimit(); }

    @Override public synchronized Map<Short, TradeItemSnapshot> equipmentItems() {
        Map<Short, TradeItemSnapshot> result = new HashMap<>();
        for (Item item : getInventory(InventoryType.EQUIP).items()) {
            TradeItemSnapshot snapshot = toTradeSnapshot(InventoryType.EQUIP, item, item.getQuantity());
            if (snapshot != null) result.put(item.getPosition(), snapshot);
        }
        return Map.copyOf(result);
    }

    /** 先在副本中完整验证槽位计划，再搬动原实例，任何失败均不产生部分写入。 */
    @Override public synchronized boolean applyEquipmentMoves(Map<Short, TradeItemSnapshot> expected,
                                                              List<SlotMove> moves, Set<Short> bindSlots) {
        if (moves.isEmpty() || !equipmentItems().equals(expected)) return false;
        Inventory bag = getInventory(InventoryType.EQUIP);
        Map<Short, Item> planned = new HashMap<>();
        bag.items().forEach(item -> planned.put(item.getPosition(), item));
        for (SlotMove move : moves) {
            if (move.source() == 0 || move.target() == 0 || move.source() == move.target()
                    || move.source() < -150 || move.target() < -150
                    || move.source() > bag.getSlotLimit() || move.target() > bag.getSlotLimit()) return false;
            Item source = planned.get(move.source()), target = planned.get(move.target());
            if (!(source instanceof Equip) || source.getQuantity() != 1
                    || target != null && (!(target instanceof Equip) || target.getQuantity() != 1)) return false;
            planned.put(move.target(), source);
            if (target == null) planned.remove(move.source()); else planned.put(move.source(), target);
        }
        if (bindSlots.stream().anyMatch(slot -> slot >= 0 || !(planned.get(slot) instanceof Equip))) return false;
        for (Item item : bag.items()) bag.removeItem(item.getPosition());
        planned.forEach((slot, item) -> {
            item.setPosition(slot);
            if (bindSlots.contains(slot)) item.setFlag(item.getFlag() | 8); // v83 不可交易标志
            bag.putAtSlot(slot, item);
        });
        markDirty();
        return true;
    }

    @Override public synchronized boolean removeEquipment(Map<Short, TradeItemSnapshot> expected, Set<Short> slots) {
        if (slots.isEmpty() || !equipmentItems().equals(expected)) return false;
        Inventory bag = getInventory(InventoryType.EQUIP);
        if (slots.stream().anyMatch(slot -> slot >= 0 || !(bag.getItem(slot) instanceof Equip))) return false;
        slots.forEach(bag::removeItem);
        markDirty();
        return true;
    }

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile ControlSettings controls = ControlSettings.defaults();

    @Override public ControlSettings controls() { return controls; }
    @Override public void setControls(ControlSettings controls) { requireStateAccess(); this.controls = Objects.requireNonNull(controls); }

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<Long, ActiveBuff> activeBuffs = new HashMap<>();
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<Integer, Long> skillCooldowns = new HashMap<>();

    @Override public synchronized Map<Long, ActiveBuff> buffs() { return Map.copyOf(activeBuffs); }
    @Override public synchronized void putBuff(ActiveBuff buff) { requireStateAccess(); activeBuffs.put(buff.mask(), buff); }
    @Override public synchronized void removeBuff(long mask) { requireStateAccess(); activeBuffs.remove(mask); }
    @Override public synchronized long cooldown(int skillId) { return skillCooldowns.getOrDefault(skillId, 0L); }
    @Override public synchronized void setCooldown(int skillId, long expiresAt) { requireStateAccess();
        if (expiresAt <= 0) skillCooldowns.remove(skillId); else skillCooldowns.put(skillId, expiresAt);
    }

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
    private final CharacterInventory inventory = new CharacterInventory();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final CharacterSkillBook skillBook = new CharacterSkillBook();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final CharacterQuestBook questBook = new CharacterQuestBook();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final long logicVersion;

    /** 地图内坐标（运行时，非持久化；地图 id 见 {@code map} 持久化字段）。 */
    private int x;
    private int y;
    /** 同屏动作状态，不持久化、不混入角色基础属性。 */
    private int stance;
    private int foothold;
    private int chairItemId;

    @Override public synchronized boolean ownsUsableItem(int itemId, byte type, long now) {
        Inventory selected = getInventory(InventoryType.getByType(type));
        if (selected == null) return false;
        return selected.items().stream().anyMatch(item -> item.getPosition() > 0 && item.getId() == itemId
                && item.getQuantity() > 0 && (item.getExpiration() < 0 || item.getExpiration() > now));
    }

    /** 当前所在地图对象（运行时，非持久化；进图时由频道装配，换图时更新）。 */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final CharacterRuntimeState runtimeState = new CharacterRuntimeState();

    public MapleMap getMapObject() {
        return runtimeState.mapObject();
    }

    public void setMapObject(MapleMap mapObject) { requireStateAccess();
        runtimeState.mapObject(mapObject);
    }

    /** 标记自上次落盘后已变更（持久化字段 setter / 背包/任务 mutation 内调用）。 */
    @Override
    public synchronized void markDirty() {
        runtimeState.markDirty();
    }

    @Override
    public boolean isDirty() {
        return runtimeState.isDirty();
    }

    @Override
    public synchronized void clearDirty() {
        runtimeState.clearDirty();
    }

    /** 返回当前持久化状态版本，供异步存档建立确认点。 */
    public synchronized long dirtyVersion() {
        return runtimeState.dirtyVersion();
    }

    /** 仅当存档期间没有新变更时清脏。 */
    public synchronized boolean clearDirty(long savedVersion) {
        return runtimeState.clearDirty(savedVersion);
    }

    // ---- 持久化字段手写 setter（覆盖 Lombok 生成，赋值后标脏，红线 17 增量 FLUSH 依据） ----
    // 手写同名方法后 Lombok 不再生成该字段 setter；坐标 x/y 不标脏（运行期不落库，map 已持久化）。

    public void setLevel(int level) { requireStateAccess();
        this.level = level;
        markDirty();
    }

    public void setExp(long exp) { requireStateAccess();
        this.exp = exp;
        markDirty();
    }

    public synchronized void setHp(int hp) {
        requireStateAccess();
        this.hp = hp;
        markDirty();
    }

    public synchronized void setMp(int mp) {
        requireStateAccess();
        this.mp = mp;
        markDirty();
    }

    public void setMaxHp(int maxHp) { requireStateAccess();
        this.maxHp = maxHp;
        markDirty();
    }

    public void setMaxMp(int maxMp) { requireStateAccess();
        this.maxMp = maxMp;
        markDirty();
    }

    public synchronized int getMeso() {
        return meso;
    }

    public synchronized void setMeso(int meso) { requireStateAccess();
        this.meso = meso;
        markDirty();
    }

    public void setJob(int job) { requireStateAccess();
        this.job = job;
        markDirty();
    }

    public void setMap(int map) { requireStateAccess();
        this.map = map;
        markDirty();
    }

    /**
     * @param logicVersion 创建该角色的逻辑版本（来自 VersionGate.currentVersion()）
     */
    public PlayerCharacter(long logicVersion) {
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
        requireStateAccess();
        return inventory.getOrCreate(type, slotLimitFor(type));
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
        requireStateAccess();
        return skillBook.get(skillId);
    }

    public void putSkill(SkillEntry entry) { requireStateAccess();
        skillBook.put(entry);
        markDirty();
    }

    public void removeSkill(int skillId) { requireStateAccess();
        skillBook.remove(skillId);
        markDirty();
    }

    /** 全部技能（不可变视图）。 */
    public Map<Integer, SkillEntry> skills() {
        requireStateAccess();
        return skillBook.snapshot();
    }

    /** 加载存档时放入完整任务状态。 */
    public void putQuest(QuestStatus status) { requireStateAccess();
        if (execution != null) status.bindExecution(execution);
        questBook.put(status);
        markDirty();
    }

    /** 全部任务状态（不可变视图）。 */
    public Map<Integer, QuestStatus> quests() {
        requireStateAccess();
        return questBook.snapshot();
    }

    @Override
    public synchronized boolean addItem(int itemId, int quantity, int slotMax) {
        if (quantity <= 0 || slotMax <= 0 || slotMax > Short.MAX_VALUE) {
            return false;
        }
        InventoryType type = ItemConstants.getInventoryType(itemId);
        if (type == InventoryType.UNDEFINED) {
            return false;
        }
        Inventory inv = getInventory(type);
        // 容量预检：未满槽剩余 + 空槽 × slotMax；不足不动（无副作用）
        Item template = new Item(itemId);
        long capacity = 0;
        for (Item existing : inv.items()) {
            if (existing.getPosition() > 0 && stackCompatible(existing, template)) {
                capacity += Math.max(0, slotMax - existing.getQuantity());
            }
        }
        capacity += (long) inv.freeSlots() * slotMax;
        if (capacity < quantity) {
            return false;
        }
        // 先堆叠未满槽，再分配空槽
        int remaining = quantity;
        for (Item existing : inv.items()) {
            if (existing.getPosition() <= 0 || !stackCompatible(existing, template)) {
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
    public synchronized boolean applyQuestChange(QuestChange change) {
        QuestStatus previous = questBook.get(change.questId());
        QuestStatus.State state = previous == null ? QuestStatus.State.NOT_STARTED : previous.getState();
        long money = (long) meso + change.meso(), experience = exp + change.experience();
        if (state != change.expected() || money < 0 || money > Integer.MAX_VALUE || experience < 0
                || experience > Integer.MAX_VALUE || change.questId() <= 0 || change.questId() > 65535) return false;
        EnumMap<InventoryType, Inventory> simulated = copyInventories();
        // 先在深拷贝上执行消耗，再验证奖励容量；真实背包只在全部成功后替换。
        for (var entry : change.items().entrySet()) {
            if (entry.getValue() >= 0) continue;
            if (entry.getValue() == Integer.MIN_VALUE) return false;
            InventoryType type = ItemConstants.getInventoryType(entry.getKey());
            if (type == InventoryType.UNDEFINED) return false;
            Inventory inv = simulated.get(type);
            int remaining = -entry.getValue();
            for (Item item : List.copyOf(inv.items())) {
                if (item.getId() != entry.getKey() || item.getPosition() <= 0) continue;
                int taken = Math.min(remaining, item.getQuantity());
                item.setQuantity((short) (item.getQuantity() - taken));
                if (item.getQuantity() == 0) inv.removeItem(item.getPosition());
                remaining -= taken;
                if (remaining == 0) break;
            }
            if (remaining != 0) return false;
        }
        for (var entry : change.items().entrySet()) {
            if (entry.getValue() <= 0) continue;
            InventoryType type = ItemConstants.getInventoryType(entry.getKey());
            if (type == InventoryType.UNDEFINED || type == InventoryType.EQUIP || type == InventoryType.CASH) return false;
            int limit = change.slotLimits().getOrDefault(entry.getKey(), 0);
            if (limit <= 0 || limit > Short.MAX_VALUE) return false;
            Inventory inv = simulated.get(type);
            int remaining = entry.getValue();
            Item template = new Item(entry.getKey());
            for (Item existing : inv.items()) {
                if (existing.getPosition() <= 0 || !stackCompatible(existing, template)) continue;
                int added = Math.min(remaining, Math.max(0, limit - existing.getQuantity()));
                existing.setQuantity((short) (existing.getQuantity() + added));
                remaining -= added;
                if (remaining == 0) break;
            }
            while (remaining > 0) {
                short slot = inv.getNextFreeSlot();
                if (slot <= 0) return false;
                Item item = new Item(entry.getKey());
                int count = Math.min(limit, remaining);
                item.setQuantity((short) count);
                item.setPosition(slot);
                inv.putAtSlot(slot, item);
                remaining -= count;
            }
        }
        inventory.mutableInventories().clear();
        inventory.mutableInventories().putAll(simulated);
        QuestStatus updated = new QuestStatus(change.questId());
        updated.setState(change.target());
        updated.setForfeited((previous == null ? 0 : previous.getForfeited())
                + (change.target() == QuestStatus.State.NOT_STARTED ? 1 : 0));
        updated.setCompleted((previous == null ? 0 : previous.getCompleted())
                + (change.target() == QuestStatus.State.COMPLETED ? 1 : 0));
        if (change.target() == QuestStatus.State.COMPLETED) updated.setCompletionTime(change.completedAt());
        if (execution != null) updated.bindExecution(execution);
        questBook.put(updated);
        meso = (int) money;
        exp = experience;
        markDirty();
        return true;
    }

    @Override
    public synchronized boolean moveInventoryItem(byte inventoryType, short source, short target,
                                                   int quantity, int slotMax) {
        InventoryType type = InventoryType.getByType(inventoryType);
        if (type == InventoryType.UNDEFINED || source <= 0 || target <= 0 || source == target
                || quantity <= 0 || slotMax <= 0 || slotMax > Short.MAX_VALUE) return false;
        Inventory inv = getInventory(type);
        if (source > inv.getSlotLimit() || target > inv.getSlotLimit()) return false;
        Item from = inv.getItem(source);
        Item to = inv.getItem(target);
        if (from == null || from.getQuantity() < quantity) return false;
        boolean stack = to != null && !(from instanceof Equip) && !(from instanceof PetItem)
                && from.getCashId() == 0 && from.getPetId() == 0;
        if (stack) {
            Item normalized = from.copy();
            normalized.setPosition(target);
            normalized.setQuantity(to.getQuantity());
            stack = normalized.equals(to) && Objects.equals(from.getOwner(), to.getOwner())
                    && Objects.equals(from.getGiftFrom(), to.getGiftFrom());
        }
        if (stack) {
            int moved = Math.min(quantity, slotMax - to.getQuantity());
            if (moved <= 0) return false;
            to.setQuantity((short) (to.getQuantity() + moved));
            from.setQuantity((short) (from.getQuantity() - moved));
            if (from.getQuantity() == 0) inv.removeItem(source);
        } else if (to == null) {
            Item moved = from.copy();
            moved.setQuantity((short) quantity);
            moved.setPosition(target);
            inv.putAtSlot(target, moved);
            from.setQuantity((short) (from.getQuantity() - quantity));
            if (from.getQuantity() == 0) inv.removeItem(source);
        } else {
            if (quantity != from.getQuantity()) return false;
            from.setPosition(target);
            to.setPosition(source);
            inv.putAtSlot(source, to);
            inv.putAtSlot(target, from);
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
                if (existing.getPosition() > 0 && stackCompatible(existing, new Item(itemId))) {
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
            Inventory inv = inventory.mutableInventories().get(type);
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
        // 按编号扣除只作用于背包；已穿戴装备必须先走换装入口，不能留下过期的属性投影。
        long available = inventory.mutableInventories().values().stream().flatMap(inv -> inv.items().stream())
                .filter(item -> item.getPosition() > 0 && item.getId() == itemId)
                .mapToLong(Item::getQuantity).sum();
        if (available < quantity) return false;
        int remaining = quantity;
        for (InventoryType type : InventoryType.values()) {
            Inventory inv = inventory.mutableInventories().get(type);
            if (inv == null) {
                continue;
            }
            for (Item item : List.copyOf(inv.items())) {
                if (item.getId() != itemId || item.getPosition() <= 0) {
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
        if (!hasTradeItems(items) || !removeTradeItems(inventory.mutableInventories(), items)) {
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
                || !addTradeItems(inventory.mutableInventories(), items, slotMaxByItem)) {
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
            Inventory source = inventory.mutableInventories().get(type);
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
        requireStateAccess();
        return questBook.get(questId);
    }

    @Override
    public boolean startQuest(int questId) {
        requireStateAccess();
        QuestStatus existing = questBook.get(questId);
        if (existing != null && existing.getState() == QuestStatus.State.COMPLETED) {
            return false;   // 已完成不能重开
        }
        QuestStatus qs = new QuestStatus(questId);
        qs.setState(QuestStatus.State.STARTED);
        if (execution != null) qs.bindExecution(execution);
        questBook.put(qs);
        markDirty();
        return true;
    }

    @Override
    public boolean completeQuest(int questId) {
        requireStateAccess();
        QuestStatus qs = questBook.get(questId);
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
        requireStateAccess();
        QuestStatus qs = questBook.get(questId);
        if (qs == null || qs.getState() != QuestStatus.State.STARTED) {
            return false;
        }
        qs.setProgress(key, value);
        markDirty();
        return true;
    }

    @Override
    public long logicVersion() {
        return execution == null ? logicVersion : execution.version();
    }

    // 受控写入口：Lombok 的普通 setter 无法校验频道执行归属。
    public void setId(long value) { requireStateAccess(); this.id = value; if (execution != null) markDirty(); }
    public void setAccountId(Long value) { requireStateAccess(); this.accountId = value; if (execution != null) markDirty(); }
    public void setWorld(int value) { requireStateAccess(); this.world = value; if (execution != null) markDirty(); }
    public void setName(String value) { requireStateAccess(); this.name = value; if (execution != null) markDirty(); }
    public void setGachaExp(long value) { requireStateAccess(); this.gachaExp = value; if (execution != null) markDirty(); }
    public void setHpMpUsed(int value) { requireStateAccess(); this.hpMpUsed = value; if (execution != null) markDirty(); }
    public void setSkinColor(int value) { requireStateAccess(); this.skinColor = value; if (execution != null) markDirty(); }
    public void setGender(int value) { requireStateAccess(); this.gender = value; if (execution != null) markDirty(); }
    public void setFame(int value) { requireStateAccess(); this.fame = value; if (execution != null) markDirty(); }
    public void setFquest(int value) { requireStateAccess(); this.fquest = value; if (execution != null) markDirty(); }
    public void setHair(int value) { requireStateAccess(); this.hair = value; if (execution != null) markDirty(); }
    public void setFace(int value) { requireStateAccess(); this.face = value; if (execution != null) markDirty(); }
    public void setAp(int value) { requireStateAccess(); this.ap = value; if (execution != null) markDirty(); }
    public void setSp(String value) { requireStateAccess(); this.sp = value; if (execution != null) markDirty(); }
    public void setSpawnPoint(int value) { requireStateAccess(); this.spawnPoint = value; if (execution != null) markDirty(); }
    public void setGm(int value) { requireStateAccess(); this.gm = value; if (execution != null) markDirty(); }
    public void setParty(int value) { requireStateAccess(); this.party = value; if (execution != null) markDirty(); }
    public void setBuddyCapacity(int value) { requireStateAccess(); this.buddyCapacity = value; if (execution != null) markDirty(); }
    public void setCreateDate(String value) { requireStateAccess(); this.createDate = value; if (execution != null) markDirty(); }
    public void setRank(long value) { requireStateAccess(); this.rank = value; if (execution != null) markDirty(); }
    public void setRankMove(int value) { requireStateAccess(); this.rankMove = value; if (execution != null) markDirty(); }
    public void setJobRank(long value) { requireStateAccess(); this.jobRank = value; if (execution != null) markDirty(); }
    public void setJobRankMove(int value) { requireStateAccess(); this.jobRankMove = value; if (execution != null) markDirty(); }
    public void setGuildId(int value) { requireStateAccess(); this.guildId = value; if (execution != null) markDirty(); }
    public void setGuildRank(int value) { requireStateAccess(); this.guildRank = value; if (execution != null) markDirty(); }
    public void setMessengerId(int value) { requireStateAccess(); this.messengerId = value; if (execution != null) markDirty(); }
    public void setMessengerPosition(int value) { requireStateAccess(); this.messengerPosition = value; if (execution != null) markDirty(); }
    public void setMountLevel(int value) { requireStateAccess(); this.mountLevel = value; if (execution != null) markDirty(); }
    public void setMountExp(int value) { requireStateAccess(); this.mountExp = value; if (execution != null) markDirty(); }
    public void setMountTiredness(int value) { requireStateAccess(); this.mountTiredness = value; if (execution != null) markDirty(); }
    public void setOmokWins(int value) { requireStateAccess(); this.omokWins = value; if (execution != null) markDirty(); }
    public void setOmokLosses(int value) { requireStateAccess(); this.omokLosses = value; if (execution != null) markDirty(); }
    public void setOmokTies(int value) { requireStateAccess(); this.omokTies = value; if (execution != null) markDirty(); }
    public void setMatchCardWins(int value) { requireStateAccess(); this.matchCardWins = value; if (execution != null) markDirty(); }
    public void setMatchCardLosses(int value) { requireStateAccess(); this.matchCardLosses = value; if (execution != null) markDirty(); }
    public void setMatchCardTies(int value) { requireStateAccess(); this.matchCardTies = value; if (execution != null) markDirty(); }
    public void setMerchantMesos(int value) { requireStateAccess(); this.merchantMesos = value; if (execution != null) markDirty(); }
    public void setHasMerchant(boolean value) { requireStateAccess(); this.hasMerchant = value; if (execution != null) markDirty(); }
    public void setEquipSlots(int value) { requireStateAccess(); this.equipSlots = value; if (execution != null) markDirty(); }
    public void setUseSlots(int value) { requireStateAccess(); this.useSlots = value; if (execution != null) markDirty(); }
    public void setSetupSlots(int value) { requireStateAccess(); this.setupSlots = value; if (execution != null) markDirty(); }
    public void setEtcSlots(int value) { requireStateAccess(); this.etcSlots = value; if (execution != null) markDirty(); }
    public void setFamilyId(int value) { requireStateAccess(); this.familyId = value; if (execution != null) markDirty(); }
    public void setMonsterBookCover(int value) { requireStateAccess(); this.monsterBookCover = value; if (execution != null) markDirty(); }
    public void setAllianceRank(int value) { requireStateAccess(); this.allianceRank = value; if (execution != null) markDirty(); }
    public void setVanquisherStage(int value) { requireStateAccess(); this.vanquisherStage = value; if (execution != null) markDirty(); }
    public void setAriantPoints(int value) { requireStateAccess(); this.ariantPoints = value; if (execution != null) markDirty(); }
    public void setDojoPoints(int value) { requireStateAccess(); this.dojoPoints = value; if (execution != null) markDirty(); }
    public void setLastDojoStage(int value) { requireStateAccess(); this.lastDojoStage = value; if (execution != null) markDirty(); }
    public void setFinishedDojoTutorial(boolean value) { requireStateAccess(); this.finishedDojoTutorial = value; if (execution != null) markDirty(); }
    public void setVanquisherKills(int value) { requireStateAccess(); this.vanquisherKills = value; if (execution != null) markDirty(); }
    public void setSummonValue(int value) { requireStateAccess(); this.summonValue = value; if (execution != null) markDirty(); }
    public void setPartnerId(int value) { requireStateAccess(); this.partnerId = value; if (execution != null) markDirty(); }
    public void setMarriageItemId(int value) { requireStateAccess(); this.marriageItemId = value; if (execution != null) markDirty(); }
    public void setReborns(int value) { requireStateAccess(); this.reborns = value; if (execution != null) markDirty(); }
    public void setPqPoints(int value) { requireStateAccess(); this.pqPoints = value; if (execution != null) markDirty(); }
    public void setDataString(String value) { requireStateAccess(); this.dataString = value; if (execution != null) markDirty(); }
    public void setLastLogoutTime(String value) { requireStateAccess(); this.lastLogoutTime = value; if (execution != null) markDirty(); }
    public void setLastExpGainTime(String value) { requireStateAccess(); this.lastExpGainTime = value; if (execution != null) markDirty(); }
    public void setPartySearch(boolean value) { requireStateAccess(); this.partySearch = value; if (execution != null) markDirty(); }
    public void setJailExpire(long value) { requireStateAccess(); this.jailExpire = value; if (execution != null) markDirty(); }
    public void setX(int value) { requireStateAccess(); this.x = value; }
    public void setY(int value) { requireStateAccess(); this.y = value; }
    public void setStance(int value) { requireStateAccess(); this.stance = value; }
    public void setFoothold(int value) { requireStateAccess(); this.foothold = value; }
    public void setChairItemId(int value) { requireStateAccess(); this.chairItemId = value; }
    public void setStrStat(short value) { requireStateAccess(); this.strStat = value; if (execution != null) markDirty(); }
    public void setDexStat(short value) { requireStateAccess(); this.dexStat = value; if (execution != null) markDirty(); }
    public void setLukStat(short value) { requireStateAccess(); this.lukStat = value; if (execution != null) markDirty(); }
    public void setIntStat(short value) { requireStateAccess(); this.intStat = value; if (execution != null) markDirty(); }
}
