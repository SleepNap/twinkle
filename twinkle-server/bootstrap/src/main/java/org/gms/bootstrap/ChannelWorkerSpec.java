package org.gms.bootstrap;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.role.ChannelProcessCondition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个频道 Worker 进程的静态部署清单。
 *
 * <p>{@code twinkle.worker.channels} 格式为逗号分隔的 {@code channelId:port}，例如
 * {@code 1:8584,2:8585,3:8586}。留空时兼容旧的单频道 id/port 配置。
 */
@Singleton
@Requires(condition = ChannelProcessCondition.class)
public final class ChannelWorkerSpec {

    public record Endpoint(int channelId, String host, int port) {
    }

    private final String workerId;
    private final List<Endpoint> endpoints;

    public ChannelWorkerSpec(
            @Property(name = "twinkle.worker.id", defaultValue = "") String configuredWorkerId,
            @Property(name = "twinkle.worker.channels", defaultValue = "") String configuredChannels,
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int legacyChannelId,
            @Property(name = "twinkle.net.channel.host", defaultValue = "127.0.0.1") String host,
            @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int legacyPort) {
        this.endpoints = parse(configuredChannels, legacyChannelId, host, legacyPort);
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "worker-" + endpoints.getFirst().channelId()
                : configuredWorkerId.trim();
    }

    public String workerId() {
        return workerId;
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }

    public Endpoint endpoint(int channelId) {
        return endpoints.stream().filter(endpoint -> endpoint.channelId() == channelId).findFirst().orElse(null);
    }

    private static List<Endpoint> parse(String configured, int legacyId, String host, int legacyPort) {
        if (configured == null || configured.isBlank()) {
            return List.of(validate(new Endpoint(legacyId, host, legacyPort)));
        }
        List<Endpoint> result = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        for (String item : configured.split(",")) {
            String[] fields = item.trim().split(":", -1);
            if (fields.length != 2) {
                throw new IllegalArgumentException("Invalid twinkle.worker.channels entry: " + item);
            }
            Endpoint endpoint;
            try {
                endpoint = validate(new Endpoint(Integer.parseInt(fields[0].trim()), host,
                        Integer.parseInt(fields[1].trim())));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid twinkle.worker.channels entry: " + item, e);
            }
            if (!ids.add(endpoint.channelId())) {
                throw new IllegalArgumentException("Duplicate channel id in worker: " + endpoint.channelId());
            }
            if (!ports.add(endpoint.port())) {
                throw new IllegalArgumentException("Duplicate channel port in worker: " + endpoint.port());
            }
            result.add(endpoint);
        }
        result.sort(java.util.Comparator.comparingInt(Endpoint::channelId));
        return List.copyOf(result);
    }

    private static Endpoint validate(Endpoint endpoint) {
        if (endpoint.channelId() < 1 || endpoint.channelId() > 256) {
            throw new IllegalArgumentException("channelId must be representable by v83 (1..256): "
                    + endpoint.channelId());
        }
        if (endpoint.port() <= 0 || endpoint.port() > 65535) {
            throw new IllegalArgumentException("channel port out of range: " + endpoint.port());
        }
        if (endpoint.host() == null || endpoint.host().isBlank()) {
            throw new IllegalArgumentException("channel host must not be blank");
        }
        return endpoint;
    }
}
