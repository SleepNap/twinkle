package org.gms.domain.game.spi;

import org.gms.domain.game.control.ControlSettings;

/** 操作设置的稳定状态接口，技能绑定校验复用已学习技能快照。 */
public interface ControlsState extends ProgressionState {
    public ControlSettings controls();
    public void setControls(ControlSettings controls);
}
