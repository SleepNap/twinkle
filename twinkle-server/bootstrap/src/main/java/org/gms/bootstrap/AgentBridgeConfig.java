package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.service.agent.PlayerSupportAgent;
import org.gms.service.agent.UnavailablePlayerSupportAgent;
import org.gms.service.agent.ServerAgentService;
import org.gms.service.agent.UnavailableServerAgentService;

/** 玩家值班 GM 的拓扑兜底装配：AI 关闭或纯频道进程中保持聊天 handler 可用。 */
@Factory
public final class AgentBridgeConfig {

    @Bean
    @Singleton
    @Requires(missingBeans = PlayerSupportAgent.class)
    public PlayerSupportAgent unavailablePlayerSupportAgent() {
        return new UnavailablePlayerSupportAgent();
    }

    @Bean
    @Singleton
    @Requires(missingBeans = ServerAgentService.class)
    public ServerAgentService unavailableServerAgentService() {
        return new UnavailableServerAgentService();
    }
}
