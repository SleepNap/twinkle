package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;

/**
 * 队伍成员 HP 变化通知：{@link PartyHandler} 在频道执行队列内把最新 HP/最大 HP 发给同图队员。
 * 测试可注入记录器，不需要构造完整队伍目录。
 */
public interface PartyHpNotifier {
    public void updateHp(PlayerCharacter character);
}
