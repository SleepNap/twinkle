package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.wz.WzMapCatalog;
import org.gms.wz.WzResourceKey;
import org.gms.wz.WzResourceLoader;
import org.gms.wz.WzResources;

import java.nio.file.Path;

@Singleton
public final class MapResourceLoader implements WzResourceLoader<WzMapCatalog> {

    @Override
    public WzResourceKey<WzMapCatalog> key() {
        return WzResources.MAPS;
    }

    @Override
    public WzMapCatalog load(Path wzRoot) {
        return new WzMapCatalog(wzRoot);
    }
}
