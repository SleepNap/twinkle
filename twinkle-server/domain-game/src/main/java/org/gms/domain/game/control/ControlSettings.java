package org.gms.domain.game.control;

import java.util.List;
import java.util.Map;

/** 持久化的客户端操作设置快照；嵌套值不可变，修改时整体替换。 */
public record ControlSettings(Map<Integer, Binding> bindings, List<Macro> macros, List<Integer> quickSlots) {
    public ControlSettings {
        bindings = Map.copyOf(bindings); macros = List.copyOf(macros); quickSlots = List.copyOf(quickSlots);
        if (bindings.size() > 90 || bindings.keySet().stream().anyMatch(key -> key < 0 || key >= 90)
                || macros.size() > 5 || !quickSlots.isEmpty() && quickSlots.size() != 8
                || quickSlots.stream().anyMatch(key -> key < 0 || key >= 90)) {
            throw new IllegalArgumentException("Invalid control settings shape");
        }
    }

    public record Binding(int type, int action) {
        public Binding {
            if (type < 0 || type > 8 || action < 0 || type == 0 && action != 0
                    || type >= 3 && type <= 7 && action > 255 || type == 8 && action > 4) {
                throw new IllegalArgumentException("Invalid key binding");
            }
        }
    }

    public record Macro(String name, boolean shout, List<Integer> skills) {
        public Macro {
            skills = List.copyOf(skills);
            if (name == null || name.length() > 12 || name.chars().anyMatch(Character::isISOControl)
                    || skills.size() != 3 || skills.stream().anyMatch(skill -> skill < 0)) {
                throw new IllegalArgumentException("Invalid skill macro");
            }
        }
    }

    /** 独立选择的基础操作布局：背包/装备/能力/技能/任务/地图，加攻击、跳跃、拾取。 */
    public static ControlSettings defaults() {
        return new ControlSettings(Map.of(23, new Binding(4, 1), 18, new Binding(4, 0),
                31, new Binding(4, 2), 37, new Binding(4, 3), 16, new Binding(4, 8), 50, new Binding(4, 7),
                29, new Binding(5, 52), 56, new Binding(5, 53), 57, new Binding(5, 54)), List.of(), List.of());
    }
}
