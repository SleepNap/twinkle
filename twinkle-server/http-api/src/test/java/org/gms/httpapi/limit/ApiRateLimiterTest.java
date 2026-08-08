package org.gms.httpapi.limit;

import org.gms.observability.NoopMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-1 限流单元测试：Bucket4j 令牌桶在容量/补满参数下的放行与拦截（架构 M3-1 第 1 节）。
 *
 * <p>直接以构造参数注入容量与补满秒数（生产经 Micronaut @Property 注入）。验证：
 * <ul>
 *   <li>容量内放行，超容量拦截（429 语义由过滤器产生，限流器只判定 boolean）。</li>
 *   <li>不同 key 独立桶（按来源维度公平限流）。</li>
 * </ul>
 */
class ApiRateLimiterTest {

    @Test
    void allowsWithinCapacityAndBlocksOver() {
        ApiRateLimiter limiter = new ApiRateLimiter(2, 600, new NoopMetrics());

        assertThat(limiter.tryConsume("client-1")).isTrue();
        assertThat(limiter.tryConsume("client-1")).isTrue();
        assertThat(limiter.tryConsume("client-1")).isFalse();  // 超容量，拦截
    }

    @Test
    void keysAreIsolated() {
        ApiRateLimiter limiter = new ApiRateLimiter(1, 600, new NoopMetrics());

        assertThat(limiter.tryConsume("ip-a")).isTrue();
        assertThat(limiter.tryConsume("ip-b")).isTrue();  // 不同 key 独立桶
        assertThat(limiter.tryConsume("ip-a")).isFalse();
    }
}
