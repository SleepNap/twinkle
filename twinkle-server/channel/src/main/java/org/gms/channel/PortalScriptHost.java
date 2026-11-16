package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.Portal;
import org.gms.domain.script.host.PortalScript;
import org.gms.net.packet.PacketSession;

/**
 * 传送门脚本宿主（{@link PortalScript} 的频道侧实现）。
 *
 * <p>脚本调 {@code portal.warp(mapId)} 时把请求转给 {@link MapTransitionService#warpTo}，
 * 由后者统一做目标地图校验、落点选择、进图对象下发与租约释放，脚本本身不直接改角色地图。
 */
public final class PortalScriptHost implements PortalScript {

    private final PacketSession session;
    private final PlayerCharacter character;
    private final MapTransitionService transitions;
    private final Portal portal;
    private boolean warped;

    public PortalScriptHost(PacketSession session, PlayerCharacter character,
                            MapTransitionService transitions, Portal portal) {
        this.session = session;
        this.character = character;
        this.transitions = transitions;
        this.portal = portal;
    }

    @Override
    public String name() {
        return portal.getScriptName();
    }

    @Override
    public int mapId() {
        return character.getMap();
    }

    @Override
    public void playPortalSound() {
        // 音效由客户端播放，服务端不产生网络包。
    }

    @Override
    public boolean warp(int targetMapId, String portalName) {
        if (warped) return true;
        warped = transitions.warpTo(session, targetMapId, portalName);
        return warped;
    }

    @Override
    public boolean warped() {
        return warped;
    }
}
