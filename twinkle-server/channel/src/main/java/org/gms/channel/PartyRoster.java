package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;

import java.util.List;

/**
 * 击杀结算使用的同图队员视图。
 *
 * <p>由 {@link PartyHandler} 基于频道队伍目录实现，测试可注入固定名单。返回值必须包含
 * 击杀者本人，只包含当前在线、同图且不在存档冻结/换图中的队员。
 */
public interface PartyRoster {
    public List<PlayerCharacter> membersOnMap(PlayerCharacter killer);
}
