package org.gms.service.intercoord;

/**
 * 兼容组合门面。新组件应优先只依赖 {@link PlayerPresenceService}、
 * {@link ChannelDirectoryService} 或 {@link SharedStateService} 中实际需要的端口。
 */
public interface IntercoordService
        extends PlayerPresenceService, ChannelDirectoryService, SharedStateService {
}
