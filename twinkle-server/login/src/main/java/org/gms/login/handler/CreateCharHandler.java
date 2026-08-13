package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;

/**
 * 创建角色处理（RecvOpcode.CREATE_CHAR 0x16，v83 建角）。
 *
 * <p>包结构（对齐参考项目）：{@code string name + int job + int face + int hair +
 * int hairColor + int skinColor + int top + int bottom + int shoes + int weapon +
 * byte gender}。
 *
 * <p>建角界面选好造型后客户端先发 0x15 查名，再发本包。服务端校验默认造型参数
 * （防伪造）、查重、落库，回 {@code ADD_NEW_CHAR_ENTRY}（成功）或
 * {@code DELETE_CHAR_RESPONSE}（失败弹窗）。M1 只开放冒险家职业（job=1），
 * 新手出生地蘑菇村（map=10000）。
 */
@Log4j2
public final class CreateCharHandler implements PacketHandler {


    private final LoginService loginService;

    public CreateCharHandler(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        // 建角发生在角色列表界面（CHARLIST 阶段）；登录态兜底用 AUTHED 兼容
        if (session.stage() != SessionStage.CHARLIST && session.stage() != SessionStage.AUTHED) {
            session.close(I18n.message("error.create_char.outside_stage"));
            return;
        }
        Account account = session.getAttr("account");
        if (account == null) {
            session.close(I18n.message("error.create_char.not_logged_in"));
            return;
        }
        String name = packet.readString();
        int job = packet.readInt();
        int face = packet.readInt();
        int hair = packet.readInt();
        int hairColor = packet.readInt();
        int skinColor = packet.readInt();
        int top = packet.readInt();
        int bottom = packet.readInt();
        int shoes = packet.readInt();
        int weapon = packet.readInt();
        int gender = packet.readByte();

        // 防伪造：造型参数必须是 v83 默认建角可选值（思路参考 BeiDou isNewCharDefault* 校验集）
        if (!isDefaultLook(job, gender, face, hair, hairColor, skinColor, top, bottom, shoes, weapon)) {
            log.warn(I18n.message("log.create_char.invalid_params"), account.getName(), job, gender);
            session.send(LoginPacketFactory.createCharFailed(9));
            return;
        }

        Character chr = loginService.createCharacter(
                account.getId(), 0, name, job, face, hair + hairColor, skinColor,
                top, bottom, shoes, weapon, gender);
        if (chr == null) {
            log.warn(I18n.message("log.create_char.failed"), account.getName(), name);
            session.send(LoginPacketFactory.createCharFailed(9));
            return;
        }
        log.info(I18n.message("log.create_char.created"), account.getName(), name, chr.getId(), job);
        // 建角成功后客户端不会重发角色列表，须把新角色追加进 session 缓存的选角列表，
        // 否则 CharSelectHandler 按缓存校验时误判"选角越权"（新角色不在旧列表里）。
        List<Character> characters = session.getAttr("characters");
        if (characters != null) {
            characters.add(chr);
        }
        // 新角色外观：带建角默认装备（客户端立即显示全身，非内衣）
        List<org.gms.data.entity.InventoryItemEntity> equipped = loginService.equippedItems(chr.getId());
        session.send(LoginPacketFactory.addNewCharEntry(chr, equipped));
    }

    /**
     * 校验造型参数是否为 v83 默认建角可选值。
     *
     * <p>v83 建角界面只允许在有限的默认脸型/发型/发色/肤色/装备中选择（防伪造
     * 非默认造型）。允许值来自客户端建角 UI 的事实数据（思路参考自 BeiDou
     * ItemConstants 校验集，实现为自研常量表）。M1 仅冒险家职业（job=1）。
     */
    private static boolean isDefaultLook(int job, int gender, int face, int hair,
                                         int hairColor, int skinColor, int top, int bottom,
                                         int shoes, int weapon) {
        // 职业仅开放冒险家（1）与新手（0）；其余职业拒绝
        if (job != 0 && job != 1) {
            return false;
        }
        // 脸型
        boolean faceOk = gender == 0
                ? face == 20000 || face == 20001 || face == 20002
                : face == 21000 || face == 21001 || face == 21002;
        // 发型基值：30000 系（男）/ 31000 系（女）
        boolean hairOk = gender == 0
                ? hair == 30000 || hair == 30020 || hair == 30030
                : hair == 31000 || hair == 31040 || hair == 31050;
        // 发色（独立字段）：0/2/3/7
        boolean hairColorOk = hairColor == 0 || hairColor == 2 || hairColor == 3 || hairColor == 7;
        boolean skinOk = skinColor >= 0 && skinColor < 4;
        // 上衣/裤/鞋/武器：仅默认可选装备
        boolean topOk = gender == 0
                ? top == 1040002 || top == 1040006 || top == 1040010
                : top == 1041002 || top == 1041006 || top == 1041010 || top == 1041011;
        boolean bottomOk = gender == 0
                ? bottom == 1060002 || bottom == 1060006
                : bottom == 1061002 || bottom == 1061008;
        boolean shoesOk = shoes == 1072001 || shoes == 1072005 || shoes == 1072037 || shoes == 1072038;
        boolean weaponOk = weapon == 1302000 || weapon == 1322005 || weapon == 1312004;
        return faceOk && hairOk && hairColorOk && skinOk && topOk && bottomOk && shoesOk && weaponOk;
    }
}
