package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.wz.WzResources;

/** Buff 效果来自 Skill.wz；独立资源键允许后续替换为专用 Buff 投影而不改注册中心。 */
@Singleton
public final class BuffResourceLoader extends AbstractNodeCatalogLoader {
    public BuffResourceLoader() {
        super(WzResources.BUFFS, "Skill.wz");
    }
}
