package org.gms.channel;

import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.logic.ItemSystem;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultItemSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号仓库（storage / trunk）存入、取出、金币搬运与异常路径的协议和持久化回归。
 *
 * <p>覆盖范围与边界：
 *
 * <ul>
 *   <li>被测对象是 {@link StorageService}（业务规则）与 {@link StorageHandler}（收包解析），
 *       持久化用 {@link StorageFixtures} 的内存桩，不连数据库。</li>
 *   <li>物品系统用真实 {@link DefaultItemSystem}：它的依赖只有 {@code VersionGate} 与
 *       {@code GameDataProvider}，两者都能用测试内构造的固定值满足，换来的是
 *       「精确实例搬运（snapshot/take/give）」这条被测语义真的被走了一遍，而不是被假实现掩盖。</li>
 *   <li>IO 队列用直接执行器（见 {@link StorageFixtures#inlineIo()}）：{@code persist} 不接受
 *       null 队列，且测试不能起线程、不能 sleep。</li>
 * </ul>
 */
public class StorageGameplayTest {
    private static final long ACCOUNT_ID = 1001L;
    private static final long CHARACTER_ID = 1L;
    private static final int NPC_ID = 9000021;
    private static final int LEVEL = 20;
    private static final int EQUIP_ITEM = 1302000;
    private static final int DART_ITEM = 2070000;
    private static final int CASH_ITEM = 5000000;
    /** 可堆叠消耗品：用于整理合并用例。 */
    private static final int POTION_ITEM = 2000000;
    private static final int STORE_FEE = 100;
    private static final int START_MESO = 1000;
    /** 客户端收包模式字节：4 取出、5 存入、7 金币搬运。 */
    private static final int MODE_TAKE_OUT = 4;
    private static final int MODE_STORE = 5;
    private static final int MODE_MESO = 7;

    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final StorageFixtures.Records records = new StorageFixtures.Records();
    private final StorageFixtures.Items stored = new StorageFixtures.Items();
    private final DefaultVersionGate versions = new DefaultVersionGate();
    private final GameDataProvider gameData = GameDataProvider.fixed(
            Map.of(EQUIP_ITEM, equipData(), POTION_ITEM, potionData()), Map.of());
    private final ItemSystem itemSystem = new DefaultItemSystem(versions, gameData);
    private final StorageService storage = new StorageService(StorageFixtures.WORLD, records, stored,
            StorageFixtures.inlineIo(), sessions, itemSystem, gameData);
    private final StorageHandler handler = new StorageHandler(storage);

    @Test public void openSendsStoragePacketWithSlotsMesoAndEmptyItems() {
        var session = joined();

        storage.open(session, session.character, NPC_ID);

        // 首次开窗读不到仓库头，服务端应建一行默认 4 格、金币 0、版本 0。
        assertThat(records.byAccount(account()).getSlotCount()).isEqualTo(4);
        assertThat(records.byAccount(account()).getMeso()).isZero();
        assertThat(records.byAccount(account()).getRowVersion()).isZero();
        assertThat(session.sent).hasSize(1);

        var wire = new ByteArrayInPacket(session.sent.getFirst().getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 0x16);      // 开窗模式
        assertThat(wire.readInt()).isEqualTo(NPC_ID);            // 回显 NPC
        assertThat(wire.readByte()).isEqualTo((byte) 4);         // 可用格数
        assertThat(wire.readShort()).isEqualTo((short) 0x7E);    // 开窗固定常量
        assertThat(wire.readShort()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readInt()).isZero();                     // 仓库金币
        assertThat(wire.readShort()).isZero();
        assertThat(wire.readByte()).isZero();                    // 物品件数
        assertThat(wire.readShort()).isZero();                   // 尾段保留
        assertThat(wire.readByte()).isZero();
        assertThat(wire.available()).isZero();
    }

    @Test public void storeMovesExactInstanceAndChargesFee() {
        var session = joined();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        assertThat(session.character.getInventory(InventoryType.EQUIP).getItem((short) 1))
                .isInstanceOf(Equip.class);
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        storage.store(session, session.character, 1, EQUIP_ITEM, 1);

        // 背包该槽消失、金币扣费。
        assertThat(session.character.getInventory(InventoryType.EQUIP).getItem((short) 1)).isNull();
        assertThat(session.character.getMeso()).isEqualTo(START_MESO - STORE_FEE);
        // 仓库落库的是同一件装备的完整实例属性，而不是只有一个 itemId。
        var rows = stored.storedByAccount(account());
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.getItemId()).isEqualTo(EQUIP_ITEM);
        assertThat(row.getInventoryType()).isEqualTo((int) InventoryType.EQUIP.getType());
        assertThat(row.getSlotIndex()).isZero();
        assertThat(row.getQuantity()).isOne();
        assertThat(row.getUpgradeSlots()).isEqualTo(5);
        assertThat(row.getStrStat()).isEqualTo(7);
        assertThat(row.getWAtk()).isEqualTo(30);
        assertThat(row.getWDef()).isEqualTo(2);
        // 回 0x0D 存入包，并补一个背包变更包解除客户端的背包锁。
        var wire = onlyStoragePacket(session);
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 0x0D);
        assertThat(wire.readByte()).isEqualTo((byte) 4);         // 仓库格数
        assertThat(wire.readShort()).isEqualTo((short) 4);       // 2 << 装备类型
        assertThat(wire.readShort()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readByte()).isEqualTo((byte) 1);         // 该类型现有件数
        assertThat(wire.readByte()).isEqualTo((byte) 1);         // 物品段：装备类型
        assertThat(wire.readInt()).isEqualTo(EQUIP_ITEM);
        assertThat(session.sent).hasSizeGreaterThan(1);
        var change = new ByteArrayInPacket(session.sent.getLast().getBytes());
        assertThat(change.readUnsignedShort()).isEqualTo(SendOpcode.INVENTORY_OPERATION.getValue());
    }

    @Test public void storeRejectsWhenStorageFull() {
        var session = joined();
        records.seed(account(), StorageFixtures.WORLD, 1);
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        storage.open(session, session.character, NPC_ID);
        storage.store(session, session.character, 1, EQUIP_ITEM, 1);
        session.sent.clear();

        storage.store(session, session.character, 2, EQUIP_ITEM, 1);

        // 仓满只回错误码，且不产生背包变更包、不落库。
        assertThat(session.sent).hasSize(1);
        var wire = onlyStoragePacket(session);
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 0x11);
        assertThat(wire.available()).isZero();
        assertThat(session.character.getInventory(InventoryType.EQUIP).getItem((short) 2)).isNotNull();
        assertThat(session.character.getMeso()).isEqualTo(START_MESO - STORE_FEE);
        assertThat(stored.storedByAccount(account())).hasSize(1);
        assertThat(records.byAccount(account()).getRowVersion()).isOne();
    }

    @Test public void storeRejectsCashItem() {
        var session = joined();
        assertThat(session.character.addItem(CASH_ITEM, 1, 1)).isTrue();
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        storage.store(session, session.character, 1, CASH_ITEM, 1);

        // v83 不允许现金道具进仓库：静默拒绝，不发包、不扣物品、不扣费。
        assertThat(session.sent).isEmpty();
        assertThat(session.character.getInventory(InventoryType.CASH).getItem((short) 1)).isNotNull();
        assertThat(session.character.getMeso()).isEqualTo(START_MESO);
        assertThat(stored.storedByAccount(account())).isEmpty();
    }

    @Test public void storeChargedItemStoresWholeStack() {
        var session = joined();
        assertThat(session.character.addItem(DART_ITEM, 500, 500)).isTrue();
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        // 客户端只报 quantity=1，服务端必须按槽位实际数量整叠存入。
        storage.store(session, session.character, 1, DART_ITEM, 1);

        var rows = stored.storedByAccount(account());
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.getItemId()).isEqualTo(DART_ITEM);
        assertThat(row.getInventoryType()).isEqualTo((int) InventoryType.USE.getType());
        assertThat(row.getQuantity()).isEqualTo(500);
        assertThat(session.character.getInventory(InventoryType.USE).items()).isEmpty();
        assertThat(session.character.getMeso()).isEqualTo(START_MESO - STORE_FEE);
        // 回包里写的也是 500，客户端不会看到「只存了 1 个」。
        var wire = onlyStoragePacket(session);
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 0x0D);
        assertThat(wire.readByte()).isEqualTo((byte) 4);         // 仓库格数
        assertThat(wire.readShort()).isEqualTo((short) 8);       // 2 << 消耗类型
        wire.skip(6);
        assertThat(wire.readByte()).isEqualTo((byte) 1);         // 件数
        assertThat(wire.readByte()).isEqualTo((byte) 2);         // 普通物品类型
        assertThat(wire.readInt()).isEqualTo(DART_ITEM);
        assertThat(wire.readByte()).isZero();                    // cash 标志
        wire.skip(8);                                            // 过期时间
        assertThat(wire.readShort()).isEqualTo((short) 500);
    }

    @Test public void takeOutAddsInstanceAndRenumbers() {
        var session = joined();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        ((Equip) session.character.getInventory(InventoryType.EQUIP).getItem((short) 1)).setStrStat((short) 3);
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        ((Equip) session.character.getInventory(InventoryType.EQUIP).getItem((short) 2)).setStrStat((short) 9);
        storage.open(session, session.character, NPC_ID);
        storage.store(session, session.character, 1, EQUIP_ITEM, 1);
        storage.store(session, session.character, 2, EQUIP_ITEM, 1);
        assertThat(stored.storedByAccount(account())).hasSize(2);
        session.sent.clear();

        storage.takeOut(session, session.character, (int) InventoryType.EQUIP.getType(), 0);

        // 取出的是下标 0（属性 3 那件），并作为完整实例进背包。
        var bag = session.character.getInventory(InventoryType.EQUIP);
        assertThat(bag.items()).hasSize(1);
        assertThat(bag.getItem((short) 1)).isInstanceOf(Equip.class);
        assertThat(((Equip) bag.getItem((short) 1)).getStrStat()).isEqualTo((short) 3);
        // 仓库剩余物品的下标重排为 0 起连续，客户端下标才不会错位。
        var rows = stored.storedByAccount(account());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSlotIndex()).isZero();
        assertThat(rows.getFirst().getStrStat()).isEqualTo(9);
        var wire = onlyStoragePacket(session);
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 0x09);
        assertThat(wire.readByte()).isEqualTo((byte) 4);
        assertThat(wire.readShort()).isEqualTo((short) 4);
        wire.skip(6);
        assertThat(wire.readByte()).isEqualTo((byte) 1);         // 该类剩余件数
    }

    @Test public void takeOutRejectsWhenBagFull() {
        var session = joined();
        // 装备栏只留 1 格：存进去再拿一件占满，就能在内存环境复现「背包已满」。
        session.character.setEquipSlots(1);
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        storage.open(session, session.character, NPC_ID);
        storage.store(session, session.character, 1, EQUIP_ITEM, 1);
        assertThat(session.character.getInventory(InventoryType.EQUIP).items()).isEmpty();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        session.sent.clear();

        storage.takeOut(session, session.character, (int) InventoryType.EQUIP.getType(), 0);

        assertThat(session.sent).hasSize(1);
        var wire = onlyStoragePacket(session);
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(wire.readByte()).isEqualTo((byte) 0x0A);
        assertThat(wire.available()).isZero();
        // 仓库与背包都不变。
        assertThat(stored.storedByAccount(account())).hasSize(1);
        assertThat(session.character.getInventory(InventoryType.EQUIP).items()).hasSize(1);
        assertThat(records.byAccount(account()).getRowVersion()).isOne();
    }

    @Test public void mesoMovesBothDirections() {
        var session = joined();
        session.character.setMeso(5000);
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        storage.meso(session, session.character, 1000);

        assertThat(session.character.getMeso()).isEqualTo(4000);
        assertThat(records.byAccount(account()).getMeso()).isEqualTo(1000);
        var inPacket = onlyStoragePacket(session);
        assertThat(inPacket.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(inPacket.readByte()).isEqualTo((byte) 0x13);
        assertThat(inPacket.readByte()).isEqualTo((byte) 4);
        assertThat(inPacket.readShort()).isEqualTo((short) 2);
        assertThat(inPacket.readShort()).isZero();
        assertThat(inPacket.readInt()).isZero();
        assertThat(inPacket.readInt()).isEqualTo(1000);
        assertThat(inPacket.available()).isZero();

        session.sent.clear();
        storage.meso(session, session.character, -400);

        assertThat(session.character.getMeso()).isEqualTo(4400);
        assertThat(records.byAccount(account()).getMeso()).isEqualTo(600);
        var outPacket = onlyStoragePacket(session);
        assertThat(outPacket.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        assertThat(outPacket.readByte()).isEqualTo((byte) 0x13);
        assertThat(outPacket.readByte()).isEqualTo((byte) 4);
        assertThat(outPacket.readShort()).isEqualTo((short) 2);
        assertThat(outPacket.readShort()).isZero();
        assertThat(outPacket.readInt()).isZero();
        assertThat(outPacket.readInt()).isEqualTo(600);
        assertThat(outPacket.available()).isZero();
    }

    @Test public void mesoRejectsOverflow() {
        var session = joined();
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        storage.meso(session, session.character, -1001);   // 取出超过仓库余额（0）
        storage.meso(session, session.character, 1001);    // 存入超过角色余额（1000）

        assertThat(session.sent).hasSize(2);
        for (OutPacket packet : session.sent) {
            var wire = new ByteArrayInPacket(packet.getBytes());
            assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
            assertThat(wire.readByte()).isEqualTo((byte) 0x0B);
            assertThat(wire.available()).isZero();
        }
        assertThat(session.character.getMeso()).isEqualTo(START_MESO);
        assertThat(records.byAccount(account()).getMeso()).isZero();
    }

    @Test public void operationsIgnoredWithoutOpenSession() {
        var session = joined();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();

        storage.store(session, session.character, 1, EQUIP_ITEM, 1);
        storage.takeOut(session, session.character, (int) InventoryType.EQUIP.getType(), 0);
        storage.meso(session, session.character, 100);

        // 没开窗就没有仓库会话：全部静默忽略，没有任何出包、读库或库存变动。
        assertThat(session.sent).isEmpty();
        assertThat(session.character.getInventory(InventoryType.EQUIP).items()).hasSize(1);
        assertThat(session.character.getMeso()).isEqualTo(START_MESO);
        assertThat(records.rows).isEmpty();
        assertThat(stored.rows).isEmpty();
    }

    @Test public void handlerParsesModesAndIgnoresMalformed() {
        var session = joined();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        // mode 5 存入：槽位 short + 物品 id int + 数量 short。
        handler.handle(session, body(packet -> packet.writeByte(MODE_STORE).writeShort(1)
                .writeInt(EQUIP_ITEM).writeShort(1)));
        assertThat(storageMode(session)).isEqualTo(0x0D);
        assertThat(stored.storedByAccount(account())).hasSize(1);
        session.sent.clear();

        // mode 4 取出：类型字节 + 类型内下标字节。
        handler.handle(session, body(packet -> packet.writeByte(MODE_TAKE_OUT).writeByte(1).writeByte(0)));
        assertThat(storageMode(session)).isEqualTo(0x09);
        assertThat(stored.storedByAccount(account())).isEmpty();
        session.sent.clear();

        // mode 7 金币搬运：金额 int（正数存入）。
        handler.handle(session, body(packet -> packet.writeByte(MODE_MESO).writeInt(100)));
        assertThat(storageMode(session)).isEqualTo(0x13);
        assertThat(records.byAccount(account()).getMeso()).isEqualTo(100);
        session.sent.clear();

        // mode 9 未知模式、长度不足与空包：静默忽略，不抛异常也不发包。
        handler.handle(session, body(packet -> packet.writeByte(9).writeInt(1)));
        handler.handle(session, body(packet -> packet.writeByte(MODE_STORE).writeShort(1)));
        handler.handle(session, body(packet -> packet.writeByte(MODE_TAKE_OUT)));
        handler.handle(session, new ByteArrayInPacket(new byte[0]));
        assertThat(session.sent).isEmpty();
        // 被忽略的包没有改动任何状态。
        assertThat(records.byAccount(account()).getMeso()).isEqualTo(100);
        assertThat(stored.storedByAccount(account())).isEmpty();
        assertThat(session.character.getInventory(InventoryType.EQUIP).items()).hasSize(1);
    }

    @Test public void mapChangeInvalidatesSession() {
        var session = joined();
        assertThat(itemSystem.giveItem(session.character, EQUIP_ITEM, 1)).isTrue();
        storage.open(session, session.character, NPC_ID);
        session.sent.clear();

        storage.onMapChange(session);
        storage.store(session, session.character, 1, EQUIP_ITEM, 1);

        Object attr = session.getAttr(StorageService.SESSION_ATTR);
        assertThat(attr).isNull();
        assertThat(session.sent).isEmpty();
        assertThat(session.character.getInventory(InventoryType.EQUIP).items()).hasSize(1);
        assertThat(session.character.getMeso()).isEqualTo(START_MESO);
        assertThat(stored.storedByAccount(account())).isEmpty();
    }

    @Test public void arrangeMergesSameStackItem() {
        var session = joined();
        records.seed(account(), StorageFixtures.WORLD, 4);
        long recordId = records.byAccount(account()).getStorageRecordId();
        stored.seed(StorageFixtures.item(recordId, account(), 2, 0, POTION_ITEM, 40));
        stored.seed(StorageFixtures.item(recordId, account(), 2, 1, POTION_ITEM, 50));

        storage.open(session, session.character, NPC_ID);
        storage.arrange(session, session.character);

        var rows = stored.stored(recordId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getItemId()).isEqualTo(POTION_ITEM);
        assertThat(rows.getFirst().getQuantity()).isEqualTo(90);
        assertThat(rows.getFirst().getSlotIndex()).isZero();
    }

    /** 进图会话：等级够开仓库、账号已绑定、默认带 {@link #START_MESO} 金币。 */
    private GameplayTestSession joined() {        var session = new GameplayTestSession(CHARACTER_ID, new MapleMap());
        session.character.setAccountId(ACCOUNT_ID);
        session.character.setLevel(LEVEL);
        session.character.setMeso(START_MESO);
        sessions.claim(CHARACTER_ID, session);
        return session;
    }

    private static int account() {
        return (int) ACCOUNT_ID;
    }

    /** 可堆叠消耗品模板：slotMax 200，供整理合并用例使用。 */
    private static ItemData potionData() {
        ItemData data = new ItemData(POTION_ITEM);
        data.setSlotMax(200);
        return data;
    }

    /**
     * 装备模板：槽位 1、力量 7、物攻 30、物防 2、可升级 5 次。
     *
     * <p>{@link DefaultItemSystem#giveItem} 造装备要读模板，没有模板就无法得到带属性的
     * {@link Equip} 实例（也就测不到「精确实例搬运」）。
     */
    private static ItemData equipData() {
        ItemData data = new ItemData(EQUIP_ITEM);
        data.setSlotMax(1);
        data.putStat("str", 7);
        data.putStat("wdef", 2);
        data.putStat("watk", 30);
        data.setEquipment(new EquipmentData("Wp", false, 0, 0, 0, 0, 0, 0, 2, false, false, 5));
        return data;
    }

    /** 会话里唯一的 STORAGE 包（顺带断言没有第二个仓库包）。 */
    private static ByteArrayInPacket onlyStoragePacket(GameplayTestSession session) {
        List<OutPacket> matches = new ArrayList<>();
        for (OutPacket packet : session.sent) {
            if (new ByteArrayInPacket(packet.getBytes()).readUnsignedShort() == SendOpcode.STORAGE.getValue()) {
                matches.add(packet);
            }
        }
        assertThat(matches).hasSize(1);
        return new ByteArrayInPacket(matches.getFirst().getBytes());
    }

    /** 会话里 STORAGE 包的模式字节（已跳过 opcode）。 */
    private static int storageMode(GameplayTestSession session) {
        var wire = onlyStoragePacket(session);
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.STORAGE.getValue());
        return wire.readByte() & 0xFF;
    }

    /** 拼客户端收包包体（opcode 已由 HandlerRegistry 剥离，这里只写包体）。 */
    private static InPacket body(Consumer<ByteArrayOutPacket> writer) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        writer.accept(packet);
        return new ByteArrayInPacket(packet.getBytes());
    }
}
