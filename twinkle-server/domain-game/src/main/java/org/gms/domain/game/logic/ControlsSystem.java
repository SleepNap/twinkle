package org.gms.domain.game.logic;

import org.gms.domain.game.control.ControlSettings;
import org.gms.domain.game.control.ControlSettings.Binding;
import org.gms.domain.game.control.ControlSettings.Macro;
import org.gms.domain.game.spi.ControlsState;
import java.util.List;
import java.util.Map;

/** ControlsSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface ControlsSystem {
    public boolean changeKeys(ControlsState state, Map<Integer, Binding> changes, long now);

    public boolean changeMacros(ControlsState state, List<Macro> macros, long now);

    public boolean changeQuickSlots(ControlsState state, List<Integer> keys);
}
