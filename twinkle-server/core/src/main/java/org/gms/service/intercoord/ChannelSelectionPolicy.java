package org.gms.service.intercoord;

import java.util.List;
import java.util.Optional;
import org.gms.service.intercoord.ChannelDirectoryService.ChannelInfo;

/** 频道分配策略契约；目录、连接与在线状态由稳定宿主持有。 */
public interface ChannelSelectionPolicy {
    public Optional<ChannelInfo> select(List<ChannelInfo> candidates);
}
