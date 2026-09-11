package org.gms.channel;

import java.util.Map;
import java.util.TreeMap;
import org.gms.i18n.I18n;

/** 与角色资产一起存档的版本化回执；损坏时拒绝加载，不能忽略后重复发奖。 */
public final class RewardReceiptCodec {
    private RewardReceiptCodec() { }

    public static String encode(Map<String, String> receipts) {
        StringBuilder result = new StringBuilder("v1\n");
        new TreeMap<>(receipts).forEach((key, value) -> result.append(key).append('=').append(value).append('\n'));
        return result.toString();
    }

    public static Map<String, String> decode(String value) {
        if (value == null || value.isEmpty()) return Map.of();
        String[] lines = value.split("\n");
        if (!lines[0].equals("v1")) throw invalid();
        Map<String, String> result = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("=", -1);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_.:-]{1,96}")
                    || !parts[1].matches("[0-9a-f]{64}") || result.putIfAbsent(parts[0], parts[1]) != null)
                throw invalid();
        }
        return Map.copyOf(result);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(I18n.message("error.reward.receipts_corrupt"));
    }
}
