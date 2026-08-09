package org.gms.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志规范静态扫描（架构 12.3 / 红线 9）。
 *
 * <p>扫全仓 {@code src/main/java}，两条规则：
 * <ol>
 *   <li><b>禁用 {@code e.printStackTrace()}</b>：日志用 log4j2 {@code log.error("描述", e)}。</li>
 *   <li><b>禁用 {@code System.out} / {@code System.err}</b>：输出走日志框架，统一结构化字段
 *       （ts/level/traceId/channelId/playerId，架构 12.3）。</li>
 * </ol>
 *
 * <p>放 bootstrap 模块（依赖全部模块），扫源码文件（字节码看不到这些调用）。
 */
class LoggingDisciplineTest {

    private static final Path REPO_ROOT = SqlInjectionScanTest.REPO_ROOT;

    /**
     * 放行清单：一次性 CLI 工具（如数据迁移/导入工具）用 stdout 打印结果——命令行工具
     * stdout 输出是标准惯例（用户直接看结果），不属于服务端日志规范范畴。
     */
    private static final String[] SYSTEM_OUT_WHITELIST = {
            "org/gms/bootstrap/tools/"
    };

    @Test
    void noPrintStackTraceInMainSources() throws IOException {
        List<String> offenders = scanMainJava(p -> read(p).contains("printStackTrace()"));
        assertThat(offenders)
                .as("发现 `printStackTrace()`，改为 log4j2 `log.error(\"描述\", e)`（红线 9）")
                .isEmpty();
    }

    @Test
    void noSystemOutOrErrInMainSources() throws IOException {
        List<String> offenders = scanMainJava(p -> {
            if (inWhitelist(p)) {
                return false;
            }
            String content = read(p);
            return content.contains("System.out") || content.contains("System.err");
        });
        assertThat(offenders)
                .as("发现 `System.out`/`System.err`，输出走 log4j2 日志框架（架构 12.3）")
                .isEmpty();
    }

    /** 遍历全仓 {@code src/main/java/*.java}，命中 reject 谓词的收集为违规文件。 */
    private static List<String> scanMainJava(Predicate<Path> reject) throws IOException {
        List<String> offenders = new ArrayList<>();
        Path server = REPO_ROOT.resolve("twinkle-server");
        try (Stream<Path> paths = Files.walk(server)) {
            paths.filter(LoggingDisciplineTest::isMainJavaSource)
                    .forEach(p -> {
                        if (reject.test(p)) {
                            offenders.add(REPO_ROOT.relativize(p).toString());
                        }
                    });
        }
        return offenders;
    }

    private static boolean isMainJavaSource(Path p) {
        String norm = p.toString().replace('\\', '/');
        return norm.contains("/src/main/java/") && norm.endsWith(".java");
    }

    private static boolean inWhitelist(Path p) {
        String norm = p.toString().replace('\\', '/');
        for (String w : SYSTEM_OUT_WHITELIST) {
            if (norm.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + p, e);
        }
    }
}
