package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;

/**
 * 查看所有角色界面选角处理（RecvOpcode.PICK_ALL_CHAR 0x0E）。
 *
 * <p>包结构：{@code int charId + int worldId + string macs + string hostString}。
 * 校验角色属于当前账号（防越权），成功后回 {@code SERVER_IP} 让客户端连频道服
 * （地址经构造注入，同 {@link CharSelectHandler}）。思路参考 BeiDou
 * ViewAllCharSelectedHandler，实现自研。
 */
@Log4j2
public final class PickAllCharHandler implements PacketHandler {


    private final LoginService loginService;
    private final byte[] channelIp;
    private final int channelPort;

    public PickAllCharHandler(LoginService loginService, byte[] channelIp, int channelPort) {
        this.loginService = loginService;
        this.channelIp = channelIp;
        this.channelPort = channelPort;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED && session.stage() != SessionStage.CHARLIST) {
            session.close("阶段外收到总览选角");
            return;
        }
        Account account = session.getAttr("account");
        if (account == null) {
            session.close("未登录收到总览选角");
            return;
        }
        long charId = packet.readInt();
        packet.readInt();           // world id（M1 单世界忽略）
        packet.readString();        // macs
        packet.readString();        // hostString

        List<Character> characters = loginService.charactersFor(account.getId(), 0);
        Character selected = characters.stream()
                .filter(c -> c.getId() != null && c.getId() == charId)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            session.close("总览选角越权: charId=" + charId);
            return;
        }

        session.setAttr("selectedChar", selected);
        session.transition(SessionStage.SELECTED);
        log.info("总览界面选中角色: {} (id={})", selected.getName(), charId);
        session.send(LoginPacketFactory.serverIp(channelIp, channelPort, (int) charId));
    }
}
