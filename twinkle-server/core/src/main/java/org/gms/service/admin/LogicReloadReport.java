package org.gms.service.admin;

import java.util.List;
import org.gms.module.ModuleRuntime;

/** 按进程与频道报告实际结果；超时不能作为成功。 */
public record LogicReloadReport(List<ModuleRuntime.Update> updates) {
    public LogicReloadReport { updates = List.copyOf(updates); }
    public boolean successful() {
        return !updates.isEmpty() && updates.stream().allMatch(update -> !update.targets().isEmpty()
                && update.targets().values().stream().allMatch("APPLIED"::equals));
    }
}
