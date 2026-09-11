package org.gms.net.netty.internal;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.gms.service.admin.AdminService;
import org.gms.service.intercoord.ChannelDirectoryService.ChannelInfo;
import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;


public class ClusterWzReleaseTest {
    @Test public void differentWorkerDigestsRejectBeforeAnyCommit() {
        AtomicInteger commits = new AtomicInteger();
        var service = new ClusterRemoteAdminService(id -> worker("digest-" + id, commits, new AtomicBoolean()), directory(), 1);
        assertThatThrownBy(service::reloadWz).isInstanceOf(IllegalStateException.class);
        assertThat(commits.get()).isZero();
    }
    @Test public void retrySkipsCompletedWorkersAndPreservesCandidate() {
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        AdminService a = worker("same", first, new AtomicBoolean()), b = worker("same", second, fail);
        var service = new ClusterRemoteAdminService(id -> id == 1 ? a : b, directory(), 1);
        var partial = service.reloadWz();
        assertThat(partial.failures()).containsKey("worker:2");
        fail.set(false);
        var complete = service.reloadWz();
        assertThat(complete.failures()).isEmpty();
        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(2);
        assertThat(complete.versions()).containsEntry("1/channel:1", 2L).containsEntry("2/channel:1", 2L);
    }
    @Test public void prepareAndCommitTravelThroughRpcWithDigestAndPerChannelStatus() {
        AtomicInteger commits = new AtomicInteger();
        var dispatcher = new AdminRpcDispatcher(worker("same", commits, new AtomicBoolean()));
        var prepared = dispatcher.dispatch("prepareWz", new String[0]);
        assertThat(JsonCodec.<String>decode(prepared.value(), String.class.getName())).isEqualTo("same");
        var committed = dispatcher.dispatch("commitWz", new String[]{JsonCodec.encode("same")});
        assertThat(committed.ok()).isTrue();
        var decoded = JsonCodec.<AdminService.WzReloadResult>decode(committed.value(), AdminService.WzReloadResult.class.getName());
        assertThat(decoded.digest()).isEqualTo("same");
        assertThat(decoded.versions()).containsEntry("channel:1", 2L);
    }
    private AdminService worker(String digest, AtomicInteger commits, AtomicBoolean fail) {
        return (AdminService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AdminService.class}, (proxy, method, args) -> {
            if (method.getName().equals("discardPreparedWz")) return null;
            if (method.getName().equals("prepareWz")) return digest;
            if (method.getName().equals("commitWz")) {
                commits.incrementAndGet();
                if (fail.get()) throw new IllegalStateException("worker unavailable");
                assertThat(args[0]).isEqualTo(digest);
                return new AdminService.WzReloadResult(2, Map.of(), Map.of("channel:1", 1), digest, Map.of("channel:1", 2L), Map.of());
            }
            throw new AssertionError(method);
        });
    }
    private IntercoordService directory() {
        return (IntercoordService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IntercoordService.class}, (proxy, method, args) -> {
            if (method.getName().equals("channels")) return Map.of(1, new ChannelInfo(1, "localhost", 1, 0, "a"),
                    2, new ChannelInfo(2, "localhost", 2, 0, "b"), 3, new ChannelInfo(3, "localhost", 3, 0, "a"));
            throw new AssertionError(method);
        });
    }
}
