package org.gms.httpapi.execution;

import lombok.extern.log4j.Log4j2;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.httpapi.mirror.OnlinePlayerMirror;
import org.gms.service.admin.OnlinePlayerEvents;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** player.online.list：稳定快照分页和绑定 Credential 的防篡改短期 Cursor。 */
@Log4j2
public final class OnlinePlayerPageService {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 200;
    private static final long CURSOR_TTL_SECONDS = 300;

    private final OnlinePlayerMirror mirror;
    private final ServerIdentity serverIdentity;
    private final byte[] signingKey;

    public OnlinePlayerPageService(OnlinePlayerMirror mirror, ServerIdentity serverIdentity,
                                   String configuredSigningKey) {
        this.mirror = mirror;
        this.serverIdentity = serverIdentity;
        if (configuredSigningKey == null || configuredSigningKey.isBlank()) {
            this.signingKey = new byte[32];
            new SecureRandom().nextBytes(this.signingKey);
            log.warn("未配置 TWINKLE_CURSOR_SIGNING_KEY：在线分页 Cursor 将在服务重启后失效");
        } else {
            this.signingKey = configuredSigningKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (this.signingKey.length < 32) {
                log.warn("TWINKLE_CURSOR_SIGNING_KEY 长度不足 32 字节，请更换高熵密钥");
            }
        }
    }

    public Map<String, Object> page(ApiPrincipal principal, Map<String, Object> input,
                                    String requestId, String executionId) {
        rejectExtraFields(input, SetHolder.ONLINE_INPUT_FIELDS, requestId, executionId);
        int pageSize = pageSize(input.get("pageSize"), requestId, executionId);
        String cursor = nullableString(input.get("cursor"), "cursor", 2048, requestId, executionId);
        OnlinePlayerMirror.Snapshot snapshot = mirror.snapshotState();
        long afterCharacterId = Long.MIN_VALUE;
        if (cursor != null) {
            CursorValue decoded = decode(cursor, requestId, executionId);
            if (!decoded.subjectId().equals(principal.subjectId())
                    || !decoded.credentialId().equals(principal.credentialId())
                    || !decoded.serverId().equals(serverIdentity.serverId())) {
                throw invalidCursor(requestId, executionId);
            }
            if (decoded.snapshotVersion() != snapshot.version()) {
                throw new ToolProtocolException(io.micronaut.http.HttpStatus.CONFLICT,
                        "snapshot_changed", "在线玩家快照已变化，请从第一页重新读取", true,
                        executionId, requestId, Map.of(
                        "currentSnapshotVersion", "online_" + snapshot.version()));
            }
            afterCharacterId = decoded.afterCharacterId();
        }

        final long pageAfterCharacterId = afterCharacterId;
        List<OnlinePlayerEvents.PlayerOnline> remaining = snapshot.players().stream()
                .filter(player -> player.characterId() > pageAfterCharacterId)
                .toList();
        List<OnlinePlayerEvents.PlayerOnline> page = remaining.stream().limit(pageSize).toList();
        List<Map<String, Object>> players = new ArrayList<>();
        for (OnlinePlayerEvents.PlayerOnline player : page) {
            LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
            safe.put("characterId", Long.toString(player.characterId()));
            safe.put("name", player.name());
            safe.put("level", player.level());
            safe.put("jobId", player.job());
            safe.put("mapId", player.mapId());
            players.add(safe);
        }

        String nextCursor = null;
        if (remaining.size() > page.size() && !page.isEmpty()) {
            nextCursor = encode(new CursorValue(snapshot.version(), page.getLast().characterId(),
                    Instant.now().plusSeconds(CURSOR_TTL_SECONDS).getEpochSecond(),
                    principal.subjectId(), principal.credentialId(), serverIdentity.serverId()));
        }

        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("serverId", serverIdentity.serverId());
        output.put("snapshotVersion", "online_" + snapshot.version());
        output.put("onlineCount", snapshot.players().size());
        output.put("returnedCount", players.size());
        output.put("players", players);
        output.put("nextCursor", nextCursor);
        output.put("observedAt", snapshot.observedAt().toString());
        return output;
    }

    private String encode(CursorValue value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeLong(value.snapshotVersion());
                output.writeLong(value.afterCharacterId());
                output.writeLong(value.expiresAtEpochSecond());
                output.writeUTF(value.subjectId());
                output.writeUTF(value.credentialId());
                output.writeUTF(value.serverId());
            }
            byte[] payload = bytes.toByteArray();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(hmac(payload));
        } catch (IOException e) {
            throw new IllegalStateException("生成 Cursor 失败", e);
        }
    }

    private CursorValue decode(String cursor, String requestId, String executionId) {
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) {
                throw invalidCursor(requestId, executionId);
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] payload = decoder.decode(parts[0]);
            byte[] signature = decoder.decode(parts[1]);
            if (!MessageDigest.isEqual(signature, hmac(payload))) {
                throw invalidCursor(requestId, executionId);
            }
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                CursorValue value = new CursorValue(input.readLong(), input.readLong(), input.readLong(),
                        input.readUTF(), input.readUTF(), input.readUTF());
                if (input.available() != 0 || value.expiresAtEpochSecond() < Instant.now().getEpochSecond()) {
                    throw invalidCursor(requestId, executionId);
                }
                return value;
            }
        } catch (IllegalArgumentException | IOException e) {
            if (e instanceof ToolProtocolException protocolException) {
                throw protocolException;
            }
            throw invalidCursor(requestId, executionId);
        }
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256", e);
        }
    }

    private static int pageSize(Object value, String requestId, String executionId) {
        if (value == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 1 || number.intValue() > MAX_PAGE_SIZE) {
            throw invalidInput("pageSize 必须是 1-200 的整数", requestId, executionId);
        }
        return number.intValue();
    }

    private static String nullableString(Object value, String field, int maxLength,
                                         String requestId, String executionId) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > maxLength) {
            throw invalidInput(field + " 格式无效", requestId, executionId);
        }
        return text;
    }

    private static void rejectExtraFields(Map<String, Object> input, java.util.Set<String> allowed,
                                          String requestId, String executionId) {
        for (String field : input.keySet()) {
            if (!allowed.contains(field)) {
                throw invalidInput("Tool input 包含未知字段: " + field, requestId, executionId);
            }
        }
    }

    private static ToolProtocolException invalidInput(String message, String requestId,
                                                      String executionId) {
        return new ToolProtocolException(io.micronaut.http.HttpStatus.BAD_REQUEST,
                "invalid_input", message, false, executionId, requestId, Map.of());
    }

    private static ToolProtocolException invalidCursor(String requestId, String executionId) {
        return invalidInput("cursor 无效、已过期或被篡改", requestId, executionId);
    }

    private record CursorValue(long snapshotVersion, long afterCharacterId,
                               long expiresAtEpochSecond, String subjectId,
                               String credentialId, String serverId) {
    }

    private static final class SetHolder {
        private static final java.util.Set<String> ONLINE_INPUT_FIELDS =
                java.util.Set.of("pageSize", "cursor");

        private SetHolder() {
        }
    }
}
