package org.gms.wz;

/**
 * WZ 换代的运行态参与者。
 *
 * <p>资源 loader 只负责构建不可见的候选快照；参与者负责把候选快照预投影为在线对象可用的变更。
 * {@link #prepare(WzResourceRegistry.PreparedReload)} 必须完成所有可能失败的解析与校验，返回的
 * {@link PreparedChange#publish()} 只做内存引用发布。以后新增需要刷新在线状态的 WZ 子系统，只需
 * 注册一个实现，无需修改重载编排器或管理接口。
 */
public interface WzReloadParticipant {

    /** 稳定且唯一的运行态投影名称，用于重载结果统计。 */
    String name();

    /** 基于尚未发布的新资源快照准备运行态变更；此阶段失败不会影响当前版本。 */
    PreparedChange prepare(WzResourceRegistry.PreparedReload resources);

    @FunctionalInterface
    interface PreparedChange {
        /** 发布已经完整准备好的内存变更，并返回受影响的运行态对象数。 */
        int publish();
    }
}
