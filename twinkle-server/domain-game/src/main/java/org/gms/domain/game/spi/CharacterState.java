package org.gms.domain.game.spi;

import org.gms.domain.game.quest.QuestStatus;
import org.gms.hotreload.versioned.Versioned;

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

    short getStr();

    short getDex();

    short getLuk();

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

    /** 持有数量（跨背包类型合计）。 */
    int getItemCount(int itemId);

    /**
     * 尝试扣物品（跨背包类型）。
     *
     * @return 持有不足时不动并返回 false；扣完返回 true
     */
    boolean removeItem(int itemId, int quantity);

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
