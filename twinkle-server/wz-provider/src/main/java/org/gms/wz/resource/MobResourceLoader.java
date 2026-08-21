package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.domain.game.mob.MobData;
import org.gms.wz.MobLoader;
import org.gms.wz.WzResourceKey;
import org.gms.wz.WzResourceLoader;
import org.gms.wz.WzResources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Singleton
public final class MobResourceLoader implements WzResourceLoader<Map<Integer, MobData>> {

    @Override
    public WzResourceKey<Map<Integer, MobData>> key() {
        return WzResources.MOBS;
    }

    @Override
    public Map<Integer, MobData> load(Path wzRoot) {
        return Files.isDirectory(wzRoot.resolve("Mob.wz"))
                ? Map.copyOf(new MobLoader(wzRoot).loadAll()) : Map.of();
    }

    @Override
    public int entryCount(Map<Integer, MobData> resource) {
        return resource.size();
    }
}
