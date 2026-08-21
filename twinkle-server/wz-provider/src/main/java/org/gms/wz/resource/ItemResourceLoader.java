package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.domain.game.item.ItemData;
import org.gms.wz.ItemLoader;
import org.gms.wz.WzResourceKey;
import org.gms.wz.WzResourceLoader;
import org.gms.wz.WzResources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Singleton
public final class ItemResourceLoader implements WzResourceLoader<Map<Integer, ItemData>> {

    @Override
    public WzResourceKey<Map<Integer, ItemData>> key() {
        return WzResources.ITEMS;
    }

    @Override
    public Map<Integer, ItemData> load(Path wzRoot) {
        return Files.isDirectory(wzRoot.resolve("Item.wz"))
                ? Map.copyOf(new ItemLoader(wzRoot).loadAll()) : Map.of();
    }

    @Override
    public int entryCount(Map<Integer, ItemData> resource) {
        return resource.size();
    }
}
