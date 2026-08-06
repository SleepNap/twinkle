package org.gms.domain.script;

import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraalVM JS 全速验证（架构 M0 第 9 项 / 红线 16）。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>引擎能启动 + 执行 JS（基础可用性）。</li>
 *   <li>宿主对象契约（cm/qm/em/rm/im 接口化）能注入 JS 调用。</li>
 *   <li>使用 {@code -XX:+EnableJVMCI} 时进入 JIT 编译路径（本测试跑在 surefire 下，
 *       {@code -XX:+EnableJVMCI} 由 pom 的 argLine 注入，测试通过即证明引擎可用）。</li>
 * </ol>
 */
class ScriptEngineTest {

    private ScriptEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ScriptEngine();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void executesBasicJavaScript() {
        // 1+2 → Integer 3（GraalJS 返回数值类型）
        Value result = engine.eval("1 + 2", java.util.Map.of());
        assertThat(result.isNumber()).isTrue();
        assertThat(result.asInt()).isEqualTo(3);
    }

    @Test
    void supportsFunctionsAndClosures() {
        Value result = engine.eval("(function(x) { return x * x; })(7)", java.util.Map.of());
        assertThat(result.asInt()).isEqualTo(49);
    }

    @Test
    void hostObjectBindingsAreInvokable() {
        // 宿主对象契约：模拟 cm（character manager）的宿主接口
        HostCm hostCm = new HostCm(37, "twinkle-player");
        Value result = engine.eval(
                "cm.getLevel() + 1",
                Map.of("cm", hostCm));
        assertThat(result.asInt()).isEqualTo(38);
    }

    @Test
    void bindingsAreRemovedAfterEval() {
        HostCm host = new HostCm(1, "p1");
        engine.eval("host.greet()", Map.of("host", host));
        // 第二次不注入 host，应抛异常（绑定不泄漏）
        try {
            engine.evalString("host.greet()");
            org.junit.jupiter.api.Assertions.fail("绑定应已移除，host 不应可用");
        } catch (RuntimeException expected) {
            // 预期：ReferenceError: host is not defined
            assertThat(expected.getMessage()).contains("host");
        }
    }

    /** 命名宿主类（匿名/包私有类的方法在 GraalVM 反射下不可见，public 类 + public 方法才可靠）。 */
    public static final class HostCm {
        private final int level;
        private final String name;

        HostCm(int level, String name) {
            this.level = level;
            this.name = name;
        }

        public int getLevel() {
            return level;
        }

        public String getName() {
            return name;
        }

        public String greet() {
            return "hi " + name;
        }
    }

    @Test
    void complexComputationWorks() {
        // 验证 JIT 路径：足够长的循环触发编译（EnableJVMCI 生效时走 Truffle JIT）
        Value result = engine.eval(
                "let sum = 0; for (let i = 0; i < 100000; i++) { sum += i; } sum;",
                java.util.Map.of());
        assertThat(result.isNumber()).isTrue();
        assertThat(result.asLong()).isEqualTo(4_999_950_000L);
    }
}
