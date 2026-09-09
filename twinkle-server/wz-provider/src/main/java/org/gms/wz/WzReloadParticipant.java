package org.gms.wz;

/**
 * WZ 换代的运行态参与者。
 *
 * <p>资源 loader 只负责构建不可见的候选快照；参与者负责把候选快照预投影为在线对象可用的变更。
 * {@link #prepare(WzResourceRegistry.PreparedReload)} 预先解析与校验已知对象；运行态仍可变化，
 * {@link PreparedChange#publish()} 必须在所属执行入口重新校验新增对象，完成校验后才整体提交。
 * 频道参与者同时推进资源视图，校验失败保持该频道旧代，不承诺多频道同时回滚。新增 WZ 子系统只需
 * 注册一个实现，无需修改重载编排器或管理接口。
 */
public interface WzReloadParticipant {

    /** 稳定且唯一的运行态投影名称，用于重载结果统计。 */
    String name();

    /** 基于尚未发布的新资源快照准备运行态变更；此阶段失败不会影响当前版本。 */
    PreparedChange prepare(WzResourceRegistry.PreparedReload resources);

    @FunctionalInterface
    interface PreparedChange {
        /** 在所属入口核验并发布内存变更，返回受影响的对象数；不得在失败时留下半更新的频道。 */
        int publish();
    }
}
