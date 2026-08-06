package org.gms.hotreload.versioned;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本门契约测试（架构 5.3）：版本一致放行、迟到写识别为 STALE、换代版本递增、未来版本报错。
 */
class VersionGateTest {

    @Test
    @DisplayName("版本一致 → ALLOW")
    void decide_whenVersionMatches_returnsAllow() {
        VersionGate gate = new DefaultVersionGate();

        assertThat(gate.decide(DefaultVersionGate.INITIAL_VERSION))
                .isEqualTo(VersionDecision.ALLOW);
    }

    @Test
    @DisplayName("重载换代后，旧版本写 → STALE（迟到写）")
    void decide_afterReload_oldVersionIsStale() {
        VersionGate gate = new DefaultVersionGate();
        long oldVersion = gate.currentVersion();
        long newVersion = gate.onReload();

        assertThat(newVersion).isEqualTo(oldVersion + 1);
        assertThat(gate.currentVersion()).isEqualTo(newVersion);
        assertThat(gate.decide(oldVersion)).isEqualTo(VersionDecision.STALE);
    }

    @Test
    @DisplayName("decide(Versioned) 委托到 logicVersion()")
    void decide_versioned_usesLogicVersion() {
        VersionGate gate = new DefaultVersionGate();
        Versioned stale = () -> 0L;
        Versioned current = () -> gate.currentVersion();

        assertThat(gate.decide(stale)).isEqualTo(VersionDecision.STALE);
        assertThat(gate.decide(current)).isEqualTo(VersionDecision.ALLOW);
    }

    @Test
    @DisplayName("未来版本（版本号回退/写错）→ 显式异常，不静默放行")
    void decide_futureVersion_throws() {
        VersionGate gate = new DefaultVersionGate();

        assertThatThrownBy(() -> gate.decide(gate.currentVersion() + 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("高于当前逻辑版本");
    }

    @Test
    @DisplayName("多次换代版本单调递增")
    void onReload_repeatedly_monotonic() {
        VersionGate gate = new DefaultVersionGate();

        assertThat(gate.onReload()).isEqualTo(2L);
        assertThat(gate.onReload()).isEqualTo(3L);
        assertThat(gate.currentVersion()).isEqualTo(3L);
    }
}
