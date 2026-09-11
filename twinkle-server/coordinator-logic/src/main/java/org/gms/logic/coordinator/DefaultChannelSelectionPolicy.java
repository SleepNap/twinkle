package org.gms.logic.coordinator;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.gms.service.intercoord.ChannelSelectionPolicy;
import org.gms.service.intercoord.ChannelDirectoryService.ChannelInfo;

/** 默认继续选择最小频道 ID；可在本模块调整分配、负载及准入规则。 */
public final class DefaultChannelSelectionPolicy implements ChannelSelectionPolicy {
    @Override public Optional<ChannelInfo> select(List<ChannelInfo> candidates) {
        return candidates.stream().min(Comparator.comparingInt(ChannelInfo::channelId));
    }
}
