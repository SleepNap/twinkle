package org.gms.domain.script.host;

/**
 * 宿主对象契约 cm（character manager，架构 M0 第 9 项）。
 *
 * <p>脚本通过 {@code cm} 访问角色信息（等级/职业/HP/MP/位置等）并驱动对话
 * （M3-5：兼容 v83 脚本写法 + 北斗 nextlevel 写法）。
 * 实现类在 channel（NpcConversationHost）；脚本只依赖此接口，
 * 不直接触碰 {@code org.gms.domain.game.Character} 具体类（红线 11/12）。
 *
 * <p>方法签名按 v83 脚本兼容约定命名，参数与返回值用基本类型/字符串，避免宿主复杂对象泄漏。
 * 对话/能力方法为 default（不破坏既有只读实现）；真实实现（channel 宿主）覆盖。
 *
 * <p>nextlevel 变体（sendXxxLevel）思路参考自 BeiDou-Server 的 NextLevelType：
 * 发包 + 记录"下一步函数名"，由 NPC handler 双路由派发。
 */
public interface Cm {

    // ---- 只读角色信息（既有契约，抽象） ----

    /** 角色名。 */
    String getName();

    /** 等级。 */
    int getLevel();

    /** 职业 id。 */
    int getJob();

    /** 当前 HP / Max HP（v83 脚本兼容字段）。 */
    int getHp();

    int getMaxHp();

    /** 当前 MP / Max MP。 */
    int getMp();

    int getMaxMp();

    /** 当前地图 id。 */
    int getMapId();

    /** 角色 id。 */
    long getId();

    // ---- 对话（M3-5 新增，default 兼容既有实现） ----

    /** 发普通对话（msgType 0，按钮 OK）。 */
    default void sendOk(String text) {
        throw unsupported("sendOk");
    }

    /** 发普通对话（msgType 0，按钮 Next）。 */
    default void sendNext(String text) {
        throw unsupported("sendNext");
    }

    /** 发普通对话（msgType 0，按钮 Prev）。 */
    default void sendPrev(String text) {
        throw unsupported("sendPrev");
    }

    /** 发普通对话（msgType 0，按钮 Next+Prev）。 */
    default void sendNextPrev(String text) {
        throw unsupported("sendNextPrev");
    }

    /** 发是/否（msgType 1）。 */
    default void sendYesNo(String text) {
        throw unsupported("sendYesNo");
    }

    /** 发接受/拒绝（msgType 0x0C）。 */
    default void sendAcceptDecline(String text) {
        throw unsupported("sendAcceptDecline");
    }

    /** 发选项列表（msgType 4，脚本用 {@code #L..##l} 标记选项）。 */
    default void sendSimple(String text) {
        throw unsupported("sendSimple");
    }

    /** 发发型/脸型选择（msgType 7）。 */
    default void sendStyle(String text, int[] styles) {
        throw unsupported("sendStyle");
    }

    /** 发文本输入（msgType 2）。 */
    default void sendGetText(String text) {
        throw unsupported("sendGetText");
    }

    /** 发数字输入（msgType 3）。 */
    default void sendGetNumber(String text, int def, int min, int max) {
        throw unsupported("sendGetNumber");
    }

    /** 结束对话（关闭会话 + 恢复客户端操作）。 */
    default void dispose() {
        throw unsupported("dispose");
    }

    // ---- nextlevel 变体（M3-5 新增，兼容北斗 nextlevel 写法） ----

    /** sendNext + 记录 nextLevel 函数名。 */
    default void sendNextLevel(String nextLevel, String text) {
        throw unsupported("sendNextLevel");
    }

    /** sendPrev + 记录 lastLevel 函数名。 */
    default void sendLastLevel(String lastLevel, String text) {
        throw unsupported("sendLastLevel");
    }

    /** 前后翻页：sendNextPrev + 记录 last/nextLevel 函数名。 */
    default void sendLastNextLevel(String lastLevel, String nextLevel, String text) {
        throw unsupported("sendLastNextLevel");
    }

    /** sendOk + 记录 nextLevel 函数名。 */
    default void sendOkLevel(String nextLevel, String text) {
        throw unsupported("sendOkLevel");
    }

    /** 选项列表（无前缀）：level{selection} 派发。 */
    default void sendSelectLevel(String text) {
        throw unsupported("sendSelectLevel");
    }

    /** 选项列表（带前缀）：level{prefix}{selection} 派发。 */
    default void sendSelectLevel(String prefix, String text) {
        throw unsupported("sendSelectLevel");
    }

    /** 选项列表 + nextLevel 兜底：level{nextLevel}(selection) 派发。 */
    default void sendNextSelectLevel(String nextLevel, String text) {
        throw unsupported("sendNextSelectLevel");
    }

    /** 数字输入：level{nextLevel}(输入值) 派发。 */
    default void getInputNumberLevel(String nextLevel, String text, int def, int min, int max) {
        throw unsupported("getInputNumberLevel");
    }

    /** 文本输入：level{nextLevel}(输入文本) 派发。 */
    default void getInputTextLevel(String nextLevel, String text) {
        throw unsupported("getInputTextLevel");
    }

    /** 接受/拒绝：level{decLevel} / level{acceptLevel} 派发。 */
    default void sendAcceptDeclineLevel(String decLevel, String acceptLevel, String text) {
        throw unsupported("sendAcceptDeclineLevel");
    }

    /** 是/否：level{noLevel} / level{yesLevel} 派发。 */
    default void sendYesNoLevel(String noLevel, String yesLevel, String text) {
        throw unsupported("sendYesNoLevel");
    }

    // ---- 能力（M3-5 新增，default 兼容既有实现） ----

    /** 给物品（经 ItemSystem，版本门）。 */
    default void giveItem(int itemId, int quantity) {
        throw unsupported("giveItem");
    }

    /** 扣物品（经 ItemSystem，版本门）。 */
    default void takeItem(int itemId, int quantity) {
        throw unsupported("takeItem");
    }

    /** 持有数量。 */
    default int getItemQuantity(int itemId) {
        throw unsupported("getItemQuantity");
    }

    /** 是否持有（可选数量）。 */
    default boolean haveItem(int itemId, int quantity) {
        throw unsupported("haveItem");
    }

    /** 给经验。 */
    default void gainExp(int amount) {
        throw unsupported("gainExp");
    }

    /** 给金币。 */
    default void gainMeso(int amount) {
        throw unsupported("gainMeso");
    }

    /** 开始任务（经 QuestSystem，版本门）。 */
    default void startQuest(int questId) {
        throw unsupported("startQuest");
    }

    /** 完成任务（经 QuestSystem，版本门）。 */
    default void completeQuest(int questId) {
        throw unsupported("completeQuest");
    }

    /** 任务状态（0=未开始/1=进行中/2=完成）。 */
    default int getQuestStatus(int questId) {
        throw unsupported("getQuestStatus");
    }

    /** 传送（换图）。 */
    default void warp(int mapId) {
        throw unsupported("warp");
    }

    private static UnsupportedOperationException unsupported(String name) {
        return new UnsupportedOperationException("cm." + name + " 未由当前宿主实现");
    }
}
