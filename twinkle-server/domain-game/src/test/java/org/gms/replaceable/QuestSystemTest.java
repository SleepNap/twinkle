package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务系统：开始/进度/完成状态机 + 版本门。
 */
class QuestSystemTest {

    private final VersionGate versionGate = new DefaultVersionGate();
    private final QuestSystem quests = new QuestSystem(versionGate);

    @Test
    @DisplayName("开始→记进度→完成 完整流程")
    void questLifecycle() {
        Character chr = new Character(versionGate.currentVersion());

        assertThat(quests.startQuest(chr, 1000)).isTrue();
        assertThat(quests.isStarted(chr, 1000)).isTrue();

        assertThat(quests.setProgress(chr, 1000, 100_001, 3)).isTrue();
        assertThat(chr.getQuestStatus(1000).getProgress(100_001)).isEqualTo(3);

        assertThat(quests.completeQuest(chr, 1000)).isTrue();
        assertThat(quests.isCompleted(chr, 1000)).isTrue();
        assertThat(quests.isStarted(chr, 1000)).isFalse();
    }

    @Test
    @DisplayName("已完成的任务不能重开")
    void completedCannotRestart() {
        Character chr = new Character(versionGate.currentVersion());
        quests.startQuest(chr, 1000);
        quests.completeQuest(chr, 1000);

        assertThat(quests.startQuest(chr, 1000)).isFalse();
    }

    @Test
    @DisplayName("未开始的任务不能写进度/完成")
    void progressAndCompleteRejectedBeforeStart() {
        Character chr = new Character(versionGate.currentVersion());

        assertThat(quests.setProgress(chr, 1000, 1, 5)).isFalse();
        assertThat(quests.completeQuest(chr, 1000)).isFalse();
    }

    @Test
    @DisplayName("版本门拒绝换代后的迟到任务操作")
    void versionGateBlocksStaleOps() {
        Character chr = new Character(versionGate.currentVersion());

        versionGate.onReload();
        assertThat(quests.startQuest(chr, 1000)).isFalse();
        assertThat(quests.setProgress(chr, 1000, 1, 1)).isFalse();
    }
}
