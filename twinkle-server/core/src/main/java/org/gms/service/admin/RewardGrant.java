package org.gms.service.admin;

import java.util.Map;
import org.gms.i18n.I18n;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 一位角色的一笔奖励；批次号与角色 ID 共同标识业务幂等键。 */
public record RewardGrant(String batchId, long characterId, Map<Integer, Integer> items,
                          int meso, int experience) {
    public RewardGrant {
        if (batchId == null || !batchId.matches("[A-Za-z0-9_.:-]{1,96}") || characterId <= 0
                || items == null || items.size() > 100 || meso < 0 || experience < 0
                || items.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getKey() <= 0
                    || e.getValue() == null || e.getValue() <= 0 || e.getValue() > 32767)
                || items.isEmpty() && meso == 0 && experience == 0) {
            throw new IllegalArgumentException(I18n.message("error.reward.invalid"));
        }
        items = Map.copyOf(items);
    }

    /** 同一业务键不得换成另一份奖励；排序后计算摘要，不依赖 Map 迭代顺序。 */
    public String fingerprint() {
        try {
            String payload = characterId + ":" + meso + ":" + experience + ":" + new TreeMap<>(items);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
