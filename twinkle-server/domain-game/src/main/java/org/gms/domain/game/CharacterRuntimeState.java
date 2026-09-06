package org.gms.domain.game;

import org.gms.domain.game.map.MapleMap;

/** 非持久化运行态：地图对象引用与增量存档版本。 */
public final class CharacterRuntimeState {
    private MapleMap mapObject;
    private volatile boolean dirty;
    private long dirtyVersion;

    public synchronized void markDirty() { dirtyVersion++; dirty = true; }
    public boolean isDirty() { return dirty; }
    public synchronized void clearDirty() { dirty = false; }
    public synchronized long dirtyVersion() { return dirtyVersion; }
    public synchronized boolean clearDirty(long savedVersion) {
        if (dirtyVersion != savedVersion) return false;
        dirty = false;
        return true;
    }
    public MapleMap mapObject() { return mapObject; }
    public void mapObject(MapleMap mapObject) { this.mapObject = mapObject; }
}
