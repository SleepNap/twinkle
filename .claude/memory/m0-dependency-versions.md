---
name: m0-dependency-versions
description: M0 经 Maven Central 核证的最新依赖版本与关键兼容性事实
metadata:
  type: project
---

M0 调研确认的版本（2026-08，全部来自 Maven Central maven-metadata.xml / search.maven.org）：

- **Micronaut 4.10.26**（micronaut-core 线），platform BOM `io.micronaut.platform:micronaut-platform:4.10.17`。**Micronaut 5.x 是当前最新（5.1.10）但官方基线 Java 25**，与红线 16（JDK 21）互斥 → 选 4.10，守住 JDK 21。
- **Netty 4.2.17.Final**（最新，4.2 线支持虚拟线程 `Transports.newEventLoopGroup` 等）；Micronaut 4.10 platform BOM 管理 4.2.x。
- **MyBatis-Flex 1.11.8**；**SQLite JDBC 3.53.2.1**；**PostgreSQL 42.7.13**；**MySQL connector 9.7.0**。
- **Flyway Community 不支持 SQLite**（`org.flywaydb:flyway-sqlite` 是商业版，Central 404）→ 决策：三库统一自研迁移器，放弃 Flyway。**已同步更新 ARCHITECTURE.md**。
- **GraalVM JS 23.1.11**（`org.graalvm.js:js-language` + `truffle-runtime` + `polyglot`，`org.graalvm.js:js` 是 pom 聚合器不含 jar），配 `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`（EnableJVMCI 是实验选项需 unlock 前置；GraalVM JDK 原生 JVMCI 无需）。**宿主对象必须 public 类 + public 方法**（匿名/包私有类方法 GraalVM 反射不可见，"Unknown identifier"）。**log4j2 2.26.1**（3.0 仍是 beta）；**Bucket4j 8.19.0**；**ArchUnit 1.5.0**；**JUnit 6.1.2**（bom）。

**GraalVM JS 版本匹配（红线 16 JDK 21 约束下的关键事实，2026-08 实测）**：
- **自 GraalVM for JDK 21 起，JS 运行时不再随 JDK 内置**（standalone 化）→ Oracle GraalVM 21.0.11 只内置 JVMCI/Graal 编译器，无 `languages/js`、无 `org.graalvm.polyglot` 模块，JS 必须经 Maven 依赖引入。
- **JS Maven 版本必须与 JDK 的 GraalVM compiler 精确匹配**（对应 21.0.N ↔ 23.1.N，release notes 确认 21.0.1 ↔ 23.1.1）。当前 JDK 21.0.11（compiler 23.1.11）+ js-language **23.1.11** 实测 `engine=Oracle GraalVM`（编译优化 ✓）。对比：25.2.4 → Interpreted（警告要求 JDK ≥25，无编译优化）；23.1.7 → 版本检查失败（需 ≥23.1.11）。
- **查版本用官方 `repo1.maven.org/.../maven-metadata.xml`**（js-language 有到 23.1.12 / 25.2.4）；**search.maven.org 索引滞后**（曾误报 23.1.x 最高 23.1.7、latest 24.2.1），勿信其 latest/版本列表。

**How to apply:** M1 起沿用这些版本，改版本前先核 Maven Central。Micronaut 相关依赖经 platform BOM 版本统一，Netty 版本可显式覆盖 platform 管理的版本（4.2.17 > 4.2.16）。Micronaut `ApplicationContext.run(Map)` 才是键值配置入口（`run(String...)` 是 CLI 参数形式）；`:memory:` 的 `::` 会被属性解析截断，测试用文件版 SQLite。

**环境事实（2026-08 实测）**：根 pom 声明 `log4j2.version=2.26.1`（Central 存在），但本地镜像（maven-metadata-nexus-ali 暗示阿里云）未同步 log4j-api 2.26.1，`dependency:tree` 实际解析到 **2.25.3** 且编译测试正常。**并非 M0 引入的问题**，是镜像同步延迟；如遇 log4j 相关解析异常，切官方仓库或锁 2.25.3 即可。

相关：[[twinkle-project-context]]
