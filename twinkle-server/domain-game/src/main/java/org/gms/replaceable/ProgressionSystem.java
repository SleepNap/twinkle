package org.gms.replaceable;

import org.gms.domain.game.spi.ProgressionState;
import org.gms.domain.game.skill.SkillDefinition;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.skill.ActiveBuff;
import org.gms.domain.game.skill.BuffDefinition;
import org.gms.domain.game.spi.BuffState;
import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** AP/SP 的原子校验与扣点；上限、职业树和前置技能在写入前统一校验。 */
public final class ProgressionSystem {
    private final VersionGate versions;
    public ProgressionSystem(VersionGate versions) { this.versions = versions; }

    public boolean accepts(CharacterState state) { return versions.decide(state) == VersionDecision.ALLOW; }

    public boolean castBuff(BuffState state, BuffDefinition effect, long now) {
        synchronized (state) {
            if (effect == null || versions.decide(state) != VersionDecision.ALLOW || effect.stats().isEmpty()
                    || effect.durationMillis() <= 0 || effect.cooldownMillis() < 0
                    || effect.hpCost() < 0 || effect.mpCost() < 0 || state.getHp() <= effect.hpCost()
                    || state.getMp() < effect.mpCost() || state.cooldown(effect.skillId()) > now) return false;
            SkillEntry learned = state.getSkill(effect.skillId());
            if (learned == null || learned.level() != effect.level() || learned.level() <= 0
                    || learned.expiration() > 0 && learned.expiration() <= now) return false;
            if (effect.stats().entrySet().stream().anyMatch(entry -> Long.bitCount(entry.getKey()) != 1
                    || entry.getValue() < Short.MIN_VALUE || entry.getValue() > Short.MAX_VALUE)) return false;
            state.setHp(state.getHp() - effect.hpCost());
            state.setMp(state.getMp() - effect.mpCost());
            effect.stats().forEach((mask, value) -> state.putBuff(new ActiveBuff(mask, value, effect.skillId(), now + effect.durationMillis())));
            state.setCooldown(effect.skillId(), now + Math.max(500, effect.cooldownMillis()));
            state.markDirty();
            return true;
        }
    }

    /** 返回本次实际移除的属性位，已被其他技能替换的效果不会被旧技能取消。 */
    public long cancelBuff(BuffState state, int skillId, long now, boolean expiryOnly) {
        synchronized (state) {
            if (versions.decide(state) != VersionDecision.ALLOW) return 0;
            long removed = 0;
            for (ActiveBuff buff : state.buffs().values()) {
                if (expiryOnly ? buff.expiresAt() <= now || state.getHp() <= 0 : buff.skillId() == skillId) {
                    state.removeBuff(buff.mask());
                    removed |= buff.mask();
                }
            }
            return removed;
        }
    }

    public boolean allocateAp(ProgressionState state, Map<Integer, Integer> increments) {
        synchronized (state) {
            if (versions.decide(state) != VersionDecision.ALLOW || increments.isEmpty()) return false;
            long total = 0;
            for (var entry : increments.entrySet()) {
                int current = switch (entry.getKey()) {
                    case 0x40 -> state.getStrStat(); case 0x80 -> state.getDexStat();
                    case 0x100 -> state.getIntStat(); case 0x200 -> state.getLukStat(); default -> -1;
                };
                if (current < 0 || entry.getValue() <= 0 || (long) current + entry.getValue() > 32767) return false;
                total += entry.getValue();
            }
            if (total > state.getAp()) return false;
            increments.forEach((mask, value) -> {
                switch (mask) {
                    case 0x40 -> state.setStrStat((short) (state.getStrStat() + value));
                    case 0x80 -> state.setDexStat((short) (state.getDexStat() + value));
                    case 0x100 -> state.setIntStat((short) (state.getIntStat() + value));
                    case 0x200 -> state.setLukStat((short) (state.getLukStat() + value));
                    default -> throw new IllegalStateException("Validated AP mask changed");
                }
            });
            state.setAp(state.getAp() - (int) total);
            state.markDirty();
            return true;
        }
    }

    public boolean allocateSp(ProgressionState state, SkillDefinition definition) {
        synchronized (state) {
            if (definition == null || definition.maxLevel() <= 0 || state.getLevel() < definition.requiredLevel()
                    || versions.decide(state) != VersionDecision.ALLOW) return false;
            int skillJob = definition.skillId() / 10000;
            int job = state.getJob();
            boolean evan = job >= 2200 && job <= 2218;
            boolean lineage = evan ? skillJob >= 2200 && skillJob <= job
                    : skillJob >= 100 && job / 100 == skillJob / 100
                    && (skillJob % 100 == 0 || skillJob % 10 == 0 && job / 10 == skillJob / 10 || skillJob == job);
            if (!lineage) return false;
            SkillEntry current = state.getSkill(definition.skillId());
            if (current != null && current.expiration() > 0 && current.expiration() <= System.currentTimeMillis()) return false;
            int level = current == null ? 0 : current.level();
            int maximum = definition.maxLevel();
            if (!evan && skillJob % 10 == 2) maximum = Math.min(maximum, current == null ? 0 : current.masterLevel());
            if (level >= maximum) return false;
            for (var requirement : definition.prerequisites().entrySet()) {
                SkillEntry learned = state.getSkill(requirement.getKey());
                if (learned == null || learned.level() < requirement.getValue()) return false;
            }
            int index = evan ? (skillJob == 2200 ? 0 : skillJob - 2209) : 0;
            int[] points;
            try { points = Arrays.stream(state.getSp().split(",", -1)).map(String::trim).mapToInt(Integer::parseInt).toArray(); }
            catch (RuntimeException error) { return false; }
            if (index < 0 || index >= points.length || points[index] <= 0 || points[index] > (evan ? 255 : Short.MAX_VALUE)) return false;
            points[index]--;
            state.putSkill(new SkillEntry(definition.skillId(), level + 1,
                    current == null ? 0 : current.masterLevel(), current == null ? -1 : current.expiration()));
            state.setSp(Arrays.stream(points).mapToObj(Integer::toString).collect(Collectors.joining(",")));
            state.markDirty();
            return true;
        }
    }
}
