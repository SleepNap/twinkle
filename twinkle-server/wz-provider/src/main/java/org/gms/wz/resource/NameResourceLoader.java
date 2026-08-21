package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.wz.WzResources;

@Singleton
public final class NameResourceLoader extends AbstractNodeCatalogLoader {
    public NameResourceLoader() {
        super(WzResources.NAMES, "String.wz");
    }
}
