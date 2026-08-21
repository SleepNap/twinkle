package org.gms.httpapi.admin.v1.controller;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.gms.diagnostics.PacketTrace;
import org.gms.httpapi.admin.AdminAuthFilter;
import org.gms.httpapi.version.ApiRoutes;
import org.gms.service.admin.AdminService;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Web 控制台按在线角色实时开启、读取和停止临时封包监听。 */
@Controller(ApiRoutes.ADMIN_V1 + "/packet-traces")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class PacketTraceController {

    private static final int DEFAULT_LIMIT = 200;

    private final AdminService adminService;

    public PacketTraceController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** opcode 目录不含包内容，可用于构造 include/exclude 选择器。 */
    @Get("/catalog")
    public PacketTrace.Catalog catalog() {
        return adminService.packetTraceCatalog();
    }

    /** 在线角色未开启监听时返回 configured=false；角色不在线返回 404。 */
    @Get("/{characterId}{?afterSequence,limit}")
    public HttpResponse<?> snapshot(@PathVariable long characterId,
                                    @QueryValue(defaultValue = "0") long afterSequence,
                                    @QueryValue(defaultValue = "200") int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, PacketTrace.MAX_PAGE_SIZE);
        PacketTrace.Snapshot snapshot = adminService.packetTraceSnapshot(
                characterId, Math.max(0, afterSequence), safeLimit);
        return snapshot == null ? offline(characterId) : noStore(snapshot);
    }

    /** 开启或重置监听。重复调用会清空旧窗口并立即应用新过滤条件。 */
    @Put("/{characterId}")
    public HttpResponse<?> start(HttpRequest<?> request, @PathVariable long characterId,
                                 @Body StartRequest body) {
        PacketTrace.Config config;
        try {
            config = config(body);
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", "invalid_packet_trace_filter",
                    "field", e.getMessage()));
        }
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE,
                "characterId=" + characterId + ",packetTraceEnabled=false");
        PacketTrace.Snapshot snapshot = adminService.startPacketTrace(characterId, config);
        if (snapshot == null) {
            return offline(characterId);
        }
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "characterId=" + characterId + ",packetTraceEnabled=true,mode=" + config.mode()
                        + ",directions=" + config.directions() + ",opcodeCount=" + config.opcodeNames().size());
        return noStore(snapshot);
    }

    /** 停止采集，已有有界窗口继续可读直到角色断线或下一次重新开启。 */
    @Delete("/{characterId}")
    public HttpResponse<?> stop(HttpRequest<?> request, @PathVariable long characterId) {
        request.setAttribute(AdminAuthFilter.BEFORE_SUMMARY_ATTRIBUTE,
                "characterId=" + characterId + ",packetTraceEnabled=true");
        PacketTrace.Snapshot snapshot = adminService.stopPacketTrace(characterId);
        if (snapshot == null) {
            return offline(characterId);
        }
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE,
                "characterId=" + characterId + ",packetTraceEnabled=false");
        return noStore(snapshot);
    }

    private static PacketTrace.Config config(StartRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("body");
        }
        PacketTrace.FilterMode mode;
        try {
            mode = PacketTrace.FilterMode.valueOf(normalize(body.mode()));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("mode", e);
        }
        Set<PacketTrace.Direction> directions = new HashSet<>();
        for (String raw : body.directions() == null ? List.<String>of() : body.directions()) {
            try {
                directions.add(PacketTrace.Direction.valueOf(normalize(raw)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("directions", e);
            }
        }
        Set<String> opcodes = new HashSet<>();
        for (String raw : body.opcodes() == null ? List.<String>of() : body.opcodes()) {
            String opcode = normalize(raw);
            if (!opcode.matches("[A-Z][A-Z0-9_]*|0X[0-9A-F]{1,4}")) {
                throw new IllegalArgumentException("opcodes");
            }
            opcodes.add(opcode);
        }
        if (mode == PacketTrace.FilterMode.INCLUDE && opcodes.isEmpty()) {
            throw new IllegalArgumentException("opcodes");
        }
        int maxPayloadBytes = body.maxPayloadBytes() == null
                ? PacketTrace.DEFAULT_MAX_PAYLOAD_BYTES : body.maxPayloadBytes();
        if (maxPayloadBytes < PacketTrace.MIN_MAX_PAYLOAD_BYTES
                || maxPayloadBytes > PacketTrace.MAX_MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("maxPayloadBytes");
        }
        return new PacketTrace.Config(mode, directions, opcodes, maxPayloadBytes);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static HttpResponse<?> offline(long characterId) {
        return HttpResponse.notFound(Map.of("error", "character_not_online", "characterId", characterId));
    }

    private static HttpResponse<?> noStore(Object body) {
        return HttpResponse.ok(body).header(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    /** 控制台监听条件。 */
    public record StartRequest(String mode, List<String> directions,
                               List<String> opcodes, Integer maxPayloadBytes) {
    }
}
