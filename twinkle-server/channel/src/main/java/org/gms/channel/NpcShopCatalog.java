package org.gms.channel;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.gms.domain.game.wz.GameDataProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** 商店商品由服务端配置给定；配置格式 npcId=itemId:price,itemId:price，不读取客户端报价。 */
@Singleton
public final class NpcShopCatalog {
    public record Offer(int itemId, int price) { }
    private final Map<Integer, List<Offer>> shops;

    @Inject
    public NpcShopCatalog(@Property(name = "twinkle.gameplay.shop-path", defaultValue = "") String configuredPath) {
        Properties properties = new Properties();
        try (InputStream stream = configuredPath.isBlank()
                ? NpcShopCatalog.class.getResourceAsStream("/gameplay/shops.properties")
                : Files.newInputStream(Path.of(configuredPath))) {
            if (stream != null) properties.load(stream);
        } catch (IOException error) { throw new IllegalStateException("Unable to load shop configuration", error); }
        Map<Integer, List<Offer>> loaded = new TreeMap<>();
        properties.forEach((key, value) -> {
            int npcId = Integer.parseInt(key.toString());
            List<Offer> offers = new ArrayList<>();
            for (String entry : value.toString().split(",")) {
                String[] parts = entry.trim().split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Invalid shop offer: " + entry);
                int itemId = Integer.parseInt(parts[0]), price = Integer.parseInt(parts[1]);
                // 当前商店面向普通堆叠物品；装备与飞镖/子弹需独立的实例/充值报价。
                if (npcId <= 0 || itemId / 1_000_000 < 2 || itemId / 1_000_000 > 4 || price <= 0
                        || itemId / 10_000 == 207 || itemId / 10_000 == 233)
                    throw new IllegalArgumentException("Unsupported shop offer: " + entry);
                offers.add(new Offer(itemId, price));
            }
            if (offers.size() > 255) throw new IllegalArgumentException("Too many shop offers");
            loaded.put(npcId, List.copyOf(offers));
        });
        this.shops = Map.copyOf(loaded);
    }

    public List<Offer> offers(int npcId, GameDataProvider data) {
        return shops.getOrDefault(npcId, List.of()).stream().filter(offer -> data.item(offer.itemId()) != null).toList();
    }
}
