package org.gms.httpapi.admin.v1.controller;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.gms.concurrent.ThreadManager;
import org.gms.service.admin.AdminService;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 批量调用的异常必须落到对应玩家，不能使其他玩家结果丢失。 */
public class RewardControllerTest {
    @Test public void onePlayerExceptionDoesNotFailTheBatchOrSkipOtherPlayers() throws Exception {
        AdminService admin = (AdminService) Proxy.newProxyInstance(AdminService.class.getClassLoader(),
                new Class<?>[]{AdminService.class}, (proxy, method, args) -> {
                    RewardGrant grant = (RewardGrant) args[0];
                    if (grant.characterId() == 1) throw new IllegalStateException("单人失败");
                    return RewardResult.of(grant, RewardResult.Status.APPLIED);
                });
        try (var background = new ThreadManager()) {
            var controller = new RewardController(admin, background);
            var result = controller.grant(new RewardController.Batch("batch", List.of(1L, 2L, -3L),
                    Map.of(), 100, 200)).get(3, TimeUnit.SECONDS);
            assertThat(result).extracting(RewardResult::status).containsExactly(
                    RewardResult.Status.FAILED, RewardResult.Status.APPLIED, RewardResult.Status.INVALID);
        }
    }
}
