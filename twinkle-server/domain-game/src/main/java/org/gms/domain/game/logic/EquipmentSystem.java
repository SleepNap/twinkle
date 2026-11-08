package org.gms.domain.game.logic;

import org.gms.domain.game.spi.EquipmentState;
import org.gms.domain.game.spi.EquipmentState.SlotMove;
import org.gms.domain.game.spi.TradeItemSnapshot;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/** EquipmentSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface EquipmentSystem {
    public record Change(List<SlotMove> moves, Set<Short> boundSlots) {
        public Change { moves = List.copyOf(moves); boundSlots = Set.copyOf(boundSlots); }
    }

    /** 卷轴砸卷结果；诅咒会销毁装备，失败只减少升级次数。 */
    public enum ScrollOutcome { SUCCESS, FAIL, CURSE }

    /** {@code whiteScroll} 是本次实际消耗白色卷轴的记录，用于客户端播放对应效果。 */
    public record ScrollResult(ScrollOutcome outcome, boolean whiteScroll) { }

    public long dataVersion();

    public Change move(EquipmentState state, short source, short target, int quantity, long now);

    /**
     * 对已穿戴装备使用升级卷轴。
     *
     * <p>在同一角色锁内校验卷轴槽位、装备槽位、卷轴类别/限定、剩余升级次数与期限，
     * 然后消耗卷轴（按客户端勾选时一并消耗白色卷轴）并结算成功、失败或诅咒。
     * 返回 {@code null} 表示请求非法且未改写任何状态。
     *
     * <p>默认返回 {@code null} 保持旧逻辑 JAR 的二进制兼容；新实现应覆盖。
     *
     * @param whiteScroll 客户端勾选白色卷轴；背包里没有时按未勾选处理
     * @param random      成功与诅咒判定所需的随机源，每次判定调用一次 {@code nextInt(100)}
     */
    public default ScrollResult scroll(EquipmentState state, short scrollSlot, short equipSlot,
                                       boolean whiteScroll, long now, RandomGenerator random) {
        return null;
    }

    public boolean expire(EquipmentState state, long now);

    public boolean refresh(EquipmentState state, long now);
}
