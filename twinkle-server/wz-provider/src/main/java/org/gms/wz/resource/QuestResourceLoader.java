package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.wz.WzResources;

@Singleton
public final class QuestResourceLoader extends AbstractNodeCatalogLoader {
    public QuestResourceLoader() {
        super(WzResources.QUESTS, "Quest.wz");
    }
}
