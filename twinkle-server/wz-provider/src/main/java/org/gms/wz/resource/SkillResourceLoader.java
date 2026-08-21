package org.gms.wz.resource;

import jakarta.inject.Singleton;
import org.gms.wz.WzResources;

@Singleton
public final class SkillResourceLoader extends AbstractNodeCatalogLoader {
    public SkillResourceLoader() {
        super(WzResources.SKILLS, "Skill.wz");
    }
}
