package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 账号仓库（storage / trunk）收包分发。
 *
 * <p>包体首字节就是仓库模式，v83 该 opcode 没有 4 字节子操作前缀：
 * 4 取出、5 存入、6 整理、7 金币搬运、8 关闭；其余模式静默忽略。
 *
 * <p>本层只做模式分发与长度/取值合法性检查（不足长度的包直接丢弃，不让 {@link InPacket}
 * 抛出越界异常）；仓满、费用、背包容量、等级门槛等业务规则全部由 {@link StorageService}
 * 判定，这里不重复校验，也不做自动封禁。
 */
public final class StorageHandler implements PacketHandler {
    /** 会话属性名：当前角色（进图时写入，本层只读）。 */
    private static final String CHARACTER_ATTR = "character";

    /** 仓库模式字节值。 */
    private static final int MODE_TAKE_OUT = 4;
    private static final int MODE_STORE = 5;
    private static final int MODE_ARRANGE = 6;
    private static final int MODE_MESO = 7;
    private static final int MODE_CLOSE = 8;

    /** 背包类型字节合法区间：1=装备、2=消耗、3=布置、4=其他、5=现金。 */
    private static final int MIN_TYPE = 1;
    private static final int MAX_TYPE = 5;
    /** 取出包体长度：类型 1 字节 + 类型内下标 1 字节。 */
    private static final int TAKE_OUT_LENGTH = 2;
    /** 存入包体长度：背包槽位 2 字节 + 物品 id 4 字节 + 数量 2 字节。 */
    private static final int STORE_LENGTH = 8;
    /** 金币包体长度：金额 4 字节。 */
    private static final int MESO_LENGTH = 4;

    private final StorageService storage;

    public StorageHandler(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            return;
        }
        PlayerCharacter character = session.getAttr(CHARACTER_ATTR);
        if (character == null) {
            return;
        }
        if (packet.available() < 1) {
            return;
        }
        int mode = packet.readByte() & 0xFF;
        switch (mode) {
            case MODE_TAKE_OUT -> takeOut(session, character, packet);
            case MODE_STORE -> store(session, character, packet);
            case MODE_ARRANGE -> storage.arrange(session, character);
            case MODE_MESO -> meso(session, character, packet);
            case MODE_CLOSE -> storage.close(session, character);
            default -> { }
        }
    }

    /** 取出：类型字节 + 类型内下标字节（0 起）。 */
    private void takeOut(PacketSession session, PlayerCharacter character, InPacket packet) {
        if (packet.available() < TAKE_OUT_LENGTH) {
            return;
        }
        int type = packet.readByte() & 0xFF;
        int slot = packet.readByte() & 0xFF;
        if (type < MIN_TYPE || type > MAX_TYPE || slot < 0) {
            return;
        }
        storage.takeOut(session, character, type, slot);
    }

    /** 存入：背包槽位（1 起）short + 物品 id int + 数量 short。 */
    private void store(PacketSession session, PlayerCharacter character, InPacket packet) {
        if (packet.available() < STORE_LENGTH) {
            return;
        }
        int slot = packet.readShort();
        int itemId = packet.readInt();
        int quantity = packet.readShort();
        if (slot < 1 || itemId <= 0 || quantity < 1) {
            return;
        }
        storage.store(session, character, slot, itemId, quantity);
    }

    /** 金币搬运：正数存入、负数取出。 */
    private void meso(PacketSession session, PlayerCharacter character, InPacket packet) {
        if (packet.available() < MESO_LENGTH) {
            return;
        }
        storage.meso(session, character, packet.readInt());
    }
}
