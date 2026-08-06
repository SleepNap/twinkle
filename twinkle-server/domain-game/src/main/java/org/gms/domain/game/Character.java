package org.gms.domain.game;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.spi.CharacterState;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private short str;
    private short dex;
    private short luk;
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

    public org.gms.domain.game.map.MapleMap getMapObject() {
        return mapObject;
    }

    public void setMapObject(org.gms.domain.game.map.MapleMap mapObject) {
        this.mapObject = mapObject;
    }

    /**
     * @param logicVersion 创建该角色的逻辑版本（来自 VersionGate.currentVersion()）
     */
    public Character(long logicVersion) {
        this.logicVersion = logicVersion;
        // v83 新角色默认值
        this.level = 1;
        this.exp = 0;
        this.str = 12;
        this.dex = 5;
        this.luk = 4;
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
    }

    public void removeSkill(int skillId) {
        skills.remove(skillId);
    }

    /** 全部技能（不可变视图）。 */
    public Map<Integer, SkillEntry> skills() {
        return Map.copyOf(skills);
    }

    @Override
    public boolean addItem(int itemId, int quantity, int slotMax) {
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
        return true;
    }

    @Override
    public int getItemCount(int itemId) {
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
    public boolean removeItem(int itemId, int quantity) {
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
                    return true;
                }
            }
        }
        return remaining == 0;
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
        return true;
    }

    @Override
    public boolean completeQuest(int questId) {
        QuestStatus qs = quests.get(questId);
        if (qs == null || qs.getState() != QuestStatus.State.STARTED) {
            return false;
        }
        qs.setState(QuestStatus.State.COMPLETED);
        return true;
    }

    @Override
    public boolean setQuestProgress(int questId, int key, int value) {
        QuestStatus qs = quests.get(questId);
        if (qs == null || qs.getState() != QuestStatus.State.STARTED) {
            return false;
        }
        qs.setProgress(key, value);
        return true;
    }

    @Override
    public long logicVersion() {
        return logicVersion;
    }
}
