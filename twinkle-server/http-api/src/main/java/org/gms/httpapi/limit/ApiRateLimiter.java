package org.gms.httpapi.limit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.gms.observability.Metrics;

import java.time.Duration;

/**
 * 第三方 API 限流（架构 M3-1：/api/v1 用 Bucket4j，/internal/v1 无需限流或弱限流）。
 *
 * <p>令牌桶：容量（默认 100），每 refillSeconds 秒补满——即 100 req/s 峰值，
 * 2C2G 档足够（第三方 API 面，非游戏热路径）。参数经 Micronaut {@code @Property}
 * 装配（application.yml 默认，测试注入覆盖）。
 *
 * <p>每个 key 独立桶（按来源 IP 维度的公平限流），跨线程 tryConsume 由 Bucket4j 保证原子。
 */
public final class ApiRateLimiter {

    private final int capacity;
    private final int refillSeconds;
    private final Metrics metrics;
    private final java.util.concurrent.ConcurrentHashMap<String, Bucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();

    public ApiRateLimiter(int capacity, int refillSeconds, Metrics metrics) {
        this.capacity = capacity;
        this.refillSeconds = refillSeconds;
        this.metrics = metrics;
    }

    /**
     * 尝试消费一个令牌。
     *
     * @param key 限流维度（如 api_key / 来源 IP / 路径）
     * @return true=放行，false=被限流
     */
    public boolean tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> newBucket());
        boolean allowed = bucket.tryConsume(1);
        if (allowed) {
            metrics.increment("http.api.rate.allow", "key", key);
        } else {
            metrics.increment("http.api.rate.block", "key", key);
        }
        return allowed;
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofSeconds(refillSeconds)));
        return Bucket.builder().addLimit(limit).build();
    }
}
