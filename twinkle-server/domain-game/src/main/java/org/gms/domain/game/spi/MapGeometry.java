package org.gms.domain.game.spi;

import java.util.OptionalInt;

/** 业务所需的地图几何查询；不向逻辑模块暴露地图玩家表和运行资源。 */
public interface MapGeometry {
    public OptionalInt groundBelow(int x, int y);
}
