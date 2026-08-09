package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Character;
import org.gms.login.LoginPacketFactory;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;

/**
 * 选角处理（RecvOpcode.CHAR_SELECT）。
 *
 * <p>包结构：{@code int charId + string macs + string hostString}。
 * 校验角色属于当前账号（防越权），成功后回 {@code SERVER_IP} 让客户端连频道服
 * （地址经构造注入：单进程自连本进程地址，M6 分布式按配置取）。
 */
@Log4j2
public final class CharSelectHandler implements PacketHandler {


    private final byte[] channelIp;
    private final int channelPort;

    public CharSelectHandler(byte[] channelIp, int channelPort) {
        this.channelIp = channelIp;
        this.channelPort = channelPort;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.CHARLIST) {
            session.close("阶段外收到选角包");
            return;
        }
        long charId = packet.readInt();
        packet.readString(); // macs
        packet.readString(); // hostString

        List<Character> characters = session.getAttr("characters");
        Character selected = characters.stream()
                .filter(c -> c.getId() != null && c.getId() == charId)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            session.close("选角越权: charId=" + charId);
            return;
        }

        session.setAttr("selectedChar", selected);
        session.transition(SessionStage.SELECTED);
        log.info("选中角色: {} (id={})", selected.getName(), charId);
        session.send(LoginPacketFactory.serverIp(channelIp, channelPort, (int) charId));
    }
}
