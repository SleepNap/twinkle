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
- **GraalVM JS 25.2.4**（`org.graalvm.js:js-language` + `truffle-runtime` + `polyglot`，`org.graalvm.js:js` 是 pom 聚合器不含 jar），配 `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`（EnableJVMCI 是实验选项需 unlock 前置）。**宿主对象必须 public 类 + public 方法**（匿名/包私有类方法 GraalVM 反射不可见，"Unknown identifier"）。**log4j2 2.26.1**（3.0 仍是 beta）；**LangChain4j 1.18.1**；**Bucket4j 8.19.0**；**ArchUnit 1.5.0**；**JUnit 6.1.2**（bom）。

**How to apply:** M1 起沿用这些版本，改版本前先核 Maven Central。Micronaut 相关依赖经 platform BOM 版本统一，Netty 版本可显式覆盖 platform 管理的版本（4.2.17 > 4.2.16）。Micronaut `ApplicationContext.run(Map)` 才是键值配置入口（`run(String...)` 是 CLI 参数形式）；`:memory:` 的 `::` 会被属性解析截断，测试用文件版 SQLite。

相关：[[twinkle-project-context]]
