package org.gms.domain.game.spi;

import org.gms.domain.game.quest.QuestStatus;
import org.gms.hotreload.versioned.Versioned;

import java.util.Map;
import java.util.List;

/**
 * 角色状态契约（稳定层 SPI，架构第三节：可替换层经接口访问稳定层）。
 *
 * <p>游戏逻辑系统（org.gms.replaceable..）依赖本接口操作角色状态，**禁止**依赖
 * {@code org.gms.domain.game.Character} 具体类（红线 11 防 CCE / ArchUnit 规则 3 强制）。
 * 本接口只暴露逻辑系统所需的核心状态子集；其余持久化字段经具体类在稳定层内部访问。
 *
 * <p>继承 {@link Versioned}：写操作携带逻辑版本，热重载换代后迟到写被版本门识别
 * （架构 5.3）。系统写回状态前应先 {@code versionGate.decide(state)}。
 */
public interface CharacterState extends Versioned {

    // ---- 身份 / 成长 ----

    long getId();

    String getName();

    int getLevel();

    int getJob();

    long getExp();

    int getMeso();

    void setMeso(int meso);

    // ---- 基础属性 ----

    short getStrStat();

    short getDexStat();

    short getLukStat();

    short getIntStat();

    // ---- 生命 / 魔力 ----

    int getHp();

    int getMaxHp();

    int getMp();

    int getMaxMp();

    void setHp(int hp);

    void setMaxHp(int maxHp);

    void setMp(int mp);

    void setMaxMp(int maxMp);

    // ---- 位置 ----

    int getMap();

    void setMap(int map);

    int getX();

    int getY();

    void setX(int x);

    void setY(int y);

    // ---- 背包（经接口操作，可替换层不触碰 Inventory/Item 具体类，红线 11） ----

    /**
     * 尝试加物品（自动堆叠到未满槽，再分配空槽）。
     *
     * @param quantity 数量（&gt; 0）
     * @param slotMax  单槽堆叠上限（来自物品静态数据）
     * @return 空间不足时不动并返回 false；成功全部加入返回 true
     */
    boolean addItem(int itemId, int quantity, int slotMax);

    /**
     * 批量物品容量预检；所有物品共享同一组空槽，必须整体可放入才返回 true。
     * 默认拒绝，避免旧实现未提供无副作用预检时被交易结算误用。
     */
    default boolean canAddItems(Map<Integer, Integer> quantities, Map<Integer, Integer> slotMaxByItem) {
        return false;
    }

    /** 持有数量（跨背包类型合计）。 */
    int getItemCount(int itemId);

    /**
     * 尝试扣物品（跨背包类型）。
     *
     * @return 持有不足时不动并返回 false；扣完返回 true
     */
    boolean removeItem(int itemId, int quantity);

    // ---- 交易物品（精确实例，经稳定 SPI 投影） ----

    /**
     * 读取可交易背包槽位的精确快照。槽位不存在、数量非法、已穿戴装备或类型不匹配时返回 null。
     */
    default TradeItemSnapshot snapshotTradeItem(byte inventoryType, short sourcePosition, int quantity) {
        return null;
    }

    /** 结算前复验出价物品仍位于原槽位且实例属性、可用数量均未变化。 */
    default boolean hasTradeItems(List<TradeItemSnapshot> items) {
        return false;
    }

    /**
     * 模拟先移出本方出价、再接收对方物品后的容量；不得修改真实背包。
     */
    default boolean canExchangeTradeItems(List<TradeItemSnapshot> outgoing,
                                          List<TradeItemSnapshot> incoming,
                                          Map<Integer, Integer> slotMaxByItem) {
        return false;
    }

    /** 按原槽位精确移出已复验的出价物品。 */
    default boolean removeTradeItems(List<TradeItemSnapshot> items) {
        return false;
    }

    /** 保留全部实例属性接收物品；调用前必须完成整体容量预检。 */
    default boolean addTradeItems(List<TradeItemSnapshot> items,
                                  Map<Integer, Integer> slotMaxByItem) {
        return false;
    }

    // ---- 任务（经接口操作，可替换层不触碰具体实现） ----

    /** 任务状态（只读查询；无则 null）。 */
    QuestStatus getQuestStatus(int questId);

    /** 开始任务（已完成不能重开；已开始返回 true 幂等）。 */
    boolean startQuest(int questId);

    /** 完成任务（仅 STARTED 可完成）。 */
    boolean completeQuest(int questId);

    /** 记录任务进度（仅 STARTED 可写）。 */
    boolean setQuestProgress(int questId, int key, int value);

    // ---- 脏标记（L4 增量 FLUSH，红线 17：只 FLUSH 脏数据） ----

    /**
     * 标记自上次落盘后已变更（持久化字段 setter / 背包/任务 mutation 内调用）。
     * 默认空实现避免破坏既有实现；稳定层 Character 覆盖实现。
     */
    default void markDirty() {
    }

    /** 是否自上次落盘后变更（L4 增量 FLUSH 用）。 */
    default boolean isDirty() {
        return false;
    }

    /** 落盘成功后清除脏标记。 */
    default void clearDirty() {
    }
}
