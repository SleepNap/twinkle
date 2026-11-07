package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.net.packet.PacketSession;

import java.util.Map;

/**
 * 击杀经验结算与升级：只处理单人击杀收益，不做队伍分摊、掉落或金币。
 *
 * <p>经验阈值见 {@link V83ExpTable}；升级后 AP +5、非新手 SP +3，HP/MP 增量按职业
 * 大类的简化曲线计算。HP/MP 曲线是当前批次的可玩占位，后续接入技能/职业成长时再精确化。
 * 所有状态修改在频道执行队列内完成，发包只走调用方会话与同图广播。
 */
public final class KillRewardService {

    /** 一次击杀的结算结果；{@code gainedExp} 是怪物原始经验。 */
    public record Result(int gainedExp, int levelsGained) {
        public boolean leveledUp() {
            return levelsGained > 0;
        }
    }

    private final PlayerSessionRegistry sessions;

    public KillRewardService(PlayerSessionRegistry sessions) {
        this.sessions = sessions;
    }

    /**
     * 给击杀者结算经验；未升级时只发 EXP 状态，升级时发完整升级状态并广播光效。
     */
    public Result onKill(PlayerCharacter character, int exp) {
        if (character == null || exp <= 0) {
            return new Result(0, 0);
        }
        int levels = applyExperience(character, exp);
        if (sessions != null) {
            PacketSession session = sessions.get(character.getId());
            if (session != null) {
                session.send(GameplayPackets.expGain(exp));
                if (levels > 0) {
                    session.send(GameplayPackets.levelUpStats(character));
                    if (character.getMapObject() != null) {
                        sessions.broadcastToMap(character.getMapObject(),
                                PlayerPresencePackets.levelUp(character.getId()), character.getId());
                    }
                } else {
                    session.send(GameplayPackets.stats(Map.of(GameplayPackets.EXP,
                            (int) Math.min(Integer.MAX_VALUE, character.getExp()))));
                }
            }
        }
        return new Result(exp, levels);
    }

    private int applyExperience(PlayerCharacter character, int gain) {
        long exp = character.getExp() + gain;
        int level = character.getLevel();
        int levels = 0;
        while (level < 200) {
            int required = V83ExpTable.requiredForLevel(level);
            if (required <= 0 || exp < required) {
                break;
            }
            exp -= required;
            level++;
            levels++;
        }
        if (level >= 200) {
            exp = 0;
        }
        if (levels > 0) {
            int job = character.getJob();
            character.setLevel(level);
            character.setAp(character.getAp() + levels * 5);
            if (job != 0) {
                character.setSp(addSp(character.getSp(), levels * 3));
            }
            character.setMaxHp(Math.min(30000, character.getMaxHp() + levels * hpGain(job)));
            character.setMaxMp(Math.min(30000, character.getMaxMp() + levels * mpGain(job)));
            character.setHp(character.effectiveMaxHp());
            character.setMp(character.effectiveMaxMp());
        }
        character.setExp(exp);
        return levels;
    }

    /** 职业大类 HP 成长（当前批次的确定性简化值，避免随机数影响可重放测试）。 */
    private static int hpGain(int job) {
        return switch (Math.floorMod(job, 1000) / 100) {
            case 1 -> 26;
            case 2 -> 12;
            case 3, 4 -> 22;
            case 5 -> 25;
            default -> 14;
        };
    }

    /** 职业大类 MP 成长（同上）。 */
    private static int mpGain(int job) {
        return switch (Math.floorMod(job, 1000) / 100) {
            case 1 -> 5;
            case 2 -> 23;
            case 3, 4 -> 15;
            case 5 -> 20;
            default -> 11;
        };
    }

    /** 普通职业先把 SP 加到第一栏；龙神多技能书暂按同一规则，后续按职业分栏细化。 */
    private static String addSp(String encoded, int gain) {
        String text = encoded == null || encoded.isBlank() ? "0" : encoded;
        String[] fields = text.split(",", -1);
        int current = 0;
        try {
            current = fields.length == 0 ? 0 : Integer.parseInt(fields[0].trim());
        } catch (NumberFormatException ignored) {
            current = 0;
        }
        fields[0] = Integer.toString(Math.max(0, current + gain));
        return String.join(",", fields);
    }
}
