package org.gms.domain.game.map;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.domain.game.spi.CharacterState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * 地图（纯数据，稳定层，内存态权威）。静态属性由 WZ 加载填充（M2-4），
 * 运行时容器（玩家进出/传送点/刷怪点）在此维护。逻辑（进图/刷怪/掉落/广播）在
 * 可替换层系统与 channel，本类只做数据结构操作。
 *
 * <p>Lombok 生成静态属性 getter/setter（红线 11）；运行时容器用自定义方法。
 * 手动 new、不进容器（红线 4）。
 */
@Getter
@Setter
public class MapleMap {

    // ---------- 静态属性（WZ 加载填充，红线 1 字节级兼容） ----------

    private int mapId;
    /** 死亡回城地图。 */
    private int returnMapId;
    private int forcedReturnMap;
    private String mapName;
    private String streetName;
    /** 是否城镇（安全区）。 */
    private boolean town;
    /** 地图限制位掩码（禁飞/禁药/禁召唤等，v83 语义）。 */
    private int fieldLimit;
    /** 刷怪倍率。 */
    private int monsterRate;
    /** 怪物容量上限。 */
    private int mobCapacity;
    /** 每秒掉血（玩家进入即扣）。 */
    private int decHp;
    /** 回复（坐下恢复等）。 */
    private int recovery;
    /** 保护物品 id。 */
    private int protectItem;
    /** 进入时触发脚本名（L2 脚本用）。 */
    private String onUserEnter;
    /** 首次进入触发脚本名。 */
    private String onFirstUserEnter;
    /** 限时地图秒数（0 = 不限时）。 */
    private int timeLimit;
    /** 是否显示时钟。 */
    private boolean clock;

    // ---------- 运行时容器（自定义方法） ----------

    @Getter(AccessLevel.NONE)
    private final Map<Integer, Portal> portals = new HashMap<>();

    @Getter(AccessLevel.NONE)
    private final List<SpawnPoint> spawnPoints = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    private final List<MapleFoothold> footholds = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    private final List<CharacterState> characters = new ArrayList<>();

    // ---------- 传送点 ----------

    public void putPortal(Portal portal) {
        portals.put(portal.getId(), portal);
    }

    public Portal getPortal(int id) {
        return portals.get(id);
    }

    public List<Portal> portals() {
        return List.copyOf(portals.values());
    }

    // ---------- 刷怪点 ----------

    public void addSpawnPoint(SpawnPoint spawnPoint) {
        spawnPoints.add(spawnPoint);
    }

    public List<SpawnPoint> spawnPoints() {
        return List.copyOf(spawnPoints);
    }

    // ---------- 地面线段（foothold，进图/移动物理基础） ----------

    public void putFoothold(MapleFoothold foothold) {
        footholds.add(foothold);
    }

    public List<MapleFoothold> footholds() {
        return List.copyOf(footholds);
    }

    /**
     * 找 (x, y) 下方最近的可站立地面 y（v83 坐标系 y 向下，重力落地接触第一条线）。
     * 无覆盖该 x 的地面返回 empty（悬空/悬崖）。
     */
    public OptionalInt groundBelow(int x, int y) {
        return footholds.stream()
                .filter(fh -> x >= Math.min(fh.getX1(), fh.getX2())
                        && x <= Math.max(fh.getX1(), fh.getX2()))
                .mapToInt(fh -> interpolateY(fh, x))
                .filter(groundY -> groundY >= y)
                .min();
    }

    /**
     * 线段在 x 处的 y（线性插值，整数运算——与参考项目 Foothold.calculateFooting 一致，
     * 思路参考自 BeiDou-Server）。水平线直接返回 y；竖直墙（x1==x2）兜底取较高端点。
     */
    private int interpolateY(MapleFoothold fh, int x) {
        if (fh.getX1() == fh.getX2()) {
            return Math.max(fh.getY1(), fh.getY2());
        }
        if (fh.getY1() == fh.getY2()) {
            return fh.getY2();
        }
        int slope = (fh.getY1() - fh.getY2()) / (fh.getX1() - fh.getX2());
        int intercept = fh.getY1() - (slope * fh.getX1());
        return (slope * x) + intercept;
    }

    // ---------- 玩家进出（数据结构操作，进图逻辑在 channel/system） ----------

    public void addCharacter(CharacterState character) {
        characters.add(character);
    }

    public void removeCharacter(CharacterState character) {
        characters.remove(character);
    }

    public List<CharacterState> characters() {
        return List.copyOf(characters);
    }

    public int characterCount() {
        return characters.size();
    }
}
