package org.gms.wz.resource;

import org.gms.wz.WzNodeCatalog;
import org.gms.wz.WzResourceKey;
import org.gms.wz.WzResourceLoader;

import java.nio.file.Path;

abstract class AbstractNodeCatalogLoader implements WzResourceLoader<WzNodeCatalog> {

    private final WzResourceKey<WzNodeCatalog> key;
    private final String directory;

    AbstractNodeCatalogLoader(WzResourceKey<WzNodeCatalog> key, String directory) {
        this.key = key;
        this.directory = directory;
    }

    @Override
    public final WzResourceKey<WzNodeCatalog> key() {
        return key;
    }

    @Override
    public final WzNodeCatalog load(Path wzRoot) {
        return new WzNodeCatalog(wzRoot.resolve(directory));
    }

    @Override
    public final int entryCount(WzNodeCatalog resource) {
        return resource.cachedCount();
    }
}
