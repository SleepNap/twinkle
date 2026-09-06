package org.gms.replaceable;

import org.gms.domain.game.control.ControlSettings;
import org.gms.domain.game.control.ControlSettings.Binding;
import org.gms.domain.game.control.ControlSettings.Macro;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.ControlsState;
import org.gms.domain.game.wz.GameDataProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** 按键、技能宏和快捷栏统一整体校验，拒绝请求时保留原设置。 */
public final class ControlsSystem {
    private final Predicate<CharacterState> accepts;
    private final GameDataProvider data;

    public ControlsSystem(Predicate<CharacterState> accepts, GameDataProvider data) {
        this.accepts = accepts; this.data = data;
    }

    public boolean changeKeys(ControlsState state, Map<Integer, Binding> changes, long now) {
        synchronized (state) {
            if (!accepts.test(state)) return false;
            for (var entry : changes.entrySet()) {
                Binding binding = entry.getValue();
                if (entry.getKey() < 0 || entry.getKey() >= 90
                        || binding.type() == 1 && !learned(state, binding.action(), now)
                        || binding.type() == 2 && data.item(binding.action()) == null) return false;
            }
            Map<Integer, Binding> bindings = new HashMap<>(state.controls().bindings());
            changes.forEach((key, binding) -> {
                if (binding.type() == 0) bindings.remove(key); else bindings.put(key, binding);
            });
            state.setControls(new ControlSettings(bindings, state.controls().macros(), state.controls().quickSlots()));
            state.markDirty(); return true;
        }
    }

    public boolean changeMacros(ControlsState state, List<Macro> macros, long now) {
        synchronized (state) {
            if (!accepts.test(state) || macros.size() > 5 || macros.stream().flatMap(macro -> macro.skills().stream())
                    .anyMatch(skill -> skill != 0 && !learned(state, skill, now))) return false;
            state.setControls(new ControlSettings(state.controls().bindings(), macros, state.controls().quickSlots()));
            state.markDirty(); return true;
        }
    }

    public boolean changeQuickSlots(ControlsState state, List<Integer> keys) {
        synchronized (state) {
            if (!accepts.test(state) || keys.size() != 8 || keys.stream().anyMatch(key -> key < 0 || key >= 90)) return false;
            state.setControls(new ControlSettings(state.controls().bindings(), state.controls().macros(), keys));
            state.markDirty(); return true;
        }
    }

    private static boolean learned(ControlsState state, int id, long now) {
        var skill = state.getSkill(id);
        return skill != null && skill.level() > 0 && (skill.expiration() < 0 || skill.expiration() > now);
    }
}
