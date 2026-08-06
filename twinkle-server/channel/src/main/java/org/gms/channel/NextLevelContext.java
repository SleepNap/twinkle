package org.gms.channel;

/**
 * 对话路由上下文（M3-5 NPC 对话双路由，思路参考自 BeiDou-Server 的 NextLevelContext）。
 *
 * <p>存于 {@link NpcConversationHost}，记录 nextlevel 写法的"下一帧去哪"：
 * 经典脚本（status 重入）levelType 为 null → 走 {@code action} 重入；
 * nextlevel 脚本 → 按 levelType 派发 {@code level{xxx}} 函数。
 */
public final class NextLevelContext {

    /** 路由类型（null = 经典 status 重入；非 null = nextlevel 派发）。 */
    public final String levelType;
    public final String lastLevel;
    public final String nextLevel;
    public final String prefix;

    public NextLevelContext(String levelType, String lastLevel, String nextLevel, String prefix) {
        this.levelType = levelType;
        this.lastLevel = lastLevel;
        this.nextLevel = nextLevel;
        this.prefix = prefix;
    }

    @Override
    public String toString() {
        return "NextLevelContext[" + levelType + ", last=" + lastLevel + ", next=" + nextLevel + ", prefix=" + prefix + "]";
    }
}
