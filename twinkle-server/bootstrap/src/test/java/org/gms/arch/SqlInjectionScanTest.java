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
 * SQL 注入静态扫描（安全红线 18 / 架构 6.3 SQL 能力矩阵）。
 *
 * <p>扫全仓 {@code src/main/java}，两条规则：
 * <ol>
 *   <li><b>禁止 {@code ${}} 原始替换</b>：MyBatis-Flex {@code ${}} 是 SQL 注入面，业务代码必须用
 *       {@code #{}} 参数化；仅受控区 {@code db-dialect}（方言片段）与 {@code data.migrate}（迁移 DDL）
 *       允许。</li>
 *   <li><b>禁止裸 JDBC Statement</b>：非预编译 Statement 拼 SQL 即注入面；仅 {@code org.gms.data..}
 *       （DataSourceFactory 跑 PRAGMA 常量）与 {@code data.migrate}（迁移脚本）允许。其余一切查询
 *       走 MyBatis-Flex {@code #{} } 或 {@code PreparedStatement} 参数化。</li>
 * </ol>
 *
 * <p>放 bootstrap 模块（依赖全部模块），扫源码文件而非字节码（{@code ${}} 在字符串里，字节码不可见）。
 */
class SqlInjectionScanTest {

    private static final String[] RAW_SUBSTITUTION_WHITELIST = {
            "org/gms/db/dialect/", "org/gms/data/migrate/"
    };
    private static final String[] RAW_STATEMENT_WHITELIST = {
            "org/gms/data/", "org/gms/db/dialect/"
    };

    /** 仓库根（含 twinkle-server/pom.xml），包内共享（LoggingDisciplineTest 复用）。 */
    static final Path REPO_ROOT = findRepoRoot();

    @Test
    void noRawSqlSubstitutionOutsideWhitelist() throws IOException {
        List<String> offenders = scanMainJava(
                p -> !inWhitelist(p, RAW_SUBSTITUTION_WHITELIST) && read(p).contains("${"));

        assertThat(offenders)
                .as("发现 `${}`（MyBatis-Flex 原始替换 = SQL 注入面），改为 `#{}` 参数化")
                .isEmpty();
    }

    @Test
    void noRawJdbcStatementOutsideWhitelist() throws IOException {
        List<String> offenders = scanMainJava(p -> {
            if (inWhitelist(p, RAW_STATEMENT_WHITELIST)) {
                return false;
            }
            String content = read(p);
            return content.contains("createStatement") || content.contains("import java.sql.Statement");
        });

        assertThat(offenders)
                .as("发现裸 JDBC Statement（非参数化），改 MyBatis-Flex `#{}` 或 PreparedStatement")
                .isEmpty();
    }

    /** 遍历全仓 {@code src/main/java/*.java}，命中 reject 谓词的收集为违规文件。 */
    private static List<String> scanMainJava(Predicate<Path> reject) throws IOException {
        List<String> offenders = new ArrayList<>();
        Path server = REPO_ROOT.resolve("twinkle-server");
        try (Stream<Path> paths = Files.walk(server)) {
            paths.filter(SqlInjectionScanTest::isMainJavaSource)
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

    private static boolean inWhitelist(Path p, String[] whitelist) {
        String norm = p.toString().replace('\\', '/');
        for (String w : whitelist) {
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

    /** 从 user.dir 向上找含 twinkle-server/pom.xml 的目录（Maven 父工程所在地）。 */
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("twinkle-server").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录（含 twinkle-server/pom.xml）：user.dir="
                + System.getProperty("user.dir"));
    }
}
