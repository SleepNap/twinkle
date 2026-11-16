package org.gms.domain.script.host;

/**
 * 传送门脚本宿主契约（v83 脚本门，{@code scripts/portal/<scriptName>.js}）。
 *
 * <p>脚本通过 {@code portal} 全局对象操作：{@code portal.playPortalSE()} 让客户端播放音效，
 * {@code portal.warp(mapId)} 完成换图。宿主只暴露基本类型，不引用稳定层的具体角色/地图类。
 *
 * <p>行为约定：一次脚本执行内只允许第一次 {@code warp} 生效，后续调用直接返回已传送状态，
 * 避免脚本里连写两个 {@code warp} 造成重复换图。
 */
public interface PortalScript {

    /** 传送门在 WZ 里的脚本名（{@code portal.getScriptName()}）。 */
    String name();

    /** 触发脚本时角色所在的地图 id。 */
    int mapId();

    /**
     * 播放传送门音效。
     *
     * <p>v83 的音效由客户端播放，服务端没有音频通道；这里保留契约只为兼容脚本调用，
     * 实现方不需要产生网络包。
     */
    void playPortalSound();

    /** 传送到目标地图的默认落点。 */
    default boolean warp(int targetMapId) {
        return warp(targetMapId, null);
    }

    /**
     * 传送到目标地图的指定落点。
     *
     * @param targetMapId  目标地图 id
     * @param portalName   目标地图上的传送点名；{@code null} 表示默认落点
     * @return 是否已成功传送
     */
    boolean warp(int targetMapId, String portalName);

    /** 本次脚本执行期间是否已经触发过传送。 */
    boolean warped();
}
