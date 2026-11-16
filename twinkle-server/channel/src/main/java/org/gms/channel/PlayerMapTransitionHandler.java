package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.script.ScriptManager;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.Map;

/**
 * 玩家地图转移完成处理（RecvOpcode.PLAYER_MAP_TRANSFER）。
 *
 * <p>客户端收到 getCharInfo 并加载完成后发本包，是执行地图进入脚本的正确时机：此时角色已经在
 * 目标地图的运行态里，进图对象也已下发。
 *
 * <p>脚本约定（v83）：WZ 地图的 {@code onFirstUserEnter} 与 {@code onUserEnter} 字段给出脚本名，
 * 对应 {@code scripts/field/<name>.js}，宿主是 {@link FieldScriptHost}（{@code em}）。
 * 首次进入只执行一次，由 {@link MapleMap#markFirstUserEnterExecuted()} 原子抢占；脚本异常只记录
 * 日志，不影响进图本身。
 */
@Log4j2
public final class PlayerMapTransitionHandler implements PacketHandler {

    private final ScriptManager scripts;
    private final PlayerSessionRegistry sessions;

    public PlayerMapTransitionHandler() {
        this(null, null);
    }

    public PlayerMapTransitionHandler(ScriptManager scripts, PlayerSessionRegistry sessions) {
        this.scripts = scripts;
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.map_transition.outside_stage"));
            return;
        }
        PlayerCharacter chr = session.getAttr("character");
        if (chr == null) return;
        session.setAttr("mapTransition", null);
        log.info(I18n.message("log.map_transition.complete"), chr.getName(), chr.getId());
        runFieldScript(session, chr);
    }

    private void runFieldScript(PacketSession session, PlayerCharacter chr) {
        if (scripts == null) return;
        MapleMap map = chr.getMapObject();
        if (map == null) return;
        String name = null;
        String first = map.getOnFirstUserEnter();
        if (first != null && !first.isBlank() && map.markFirstUserEnterExecuted()) {
            name = first;
        } else {
            String every = map.getOnUserEnter();
            if (every != null && !every.isBlank()) name = every;
        }
        if (name == null) return;
        FieldScriptHost host = new FieldScriptHost(session, chr, sessions);
        try {
            scripts.run("field/" + name, Map.of("em", host));
        } catch (RuntimeException error) {
            // 脚本错误不能阻断进图：玩家已经在目标地图里，失败只记录并继续。
            log.error(I18n.message("log.script.field_failed"), name, error);
        }
    }
}
