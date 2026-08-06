# Twinkle

**热更新、扩展性好的冒险岛后台**（MapleStory v83 服务端）。

> 完整架构设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。设计决策、模块划分、运行拓扑均以该文档为准。

## 目录结构

```
twinkle/
├── twinkle-server/          # 后端服务（Java 多模块，包根 org.gms）
├── twinkle-web/             # 前端（架构待定，暂为占位）
├── ARCHITECTURE.md          # 架构定稿
└── LICENSE
```

## 环境要求

- **JDK：必须使用 GraalVM for JDK 21**（架构红线 16）。GraalVM 原生内置 JVMCI；普通 Temurin JDK 跑脚本引擎需额外 `-XX:+EnableJVMCI`（surefire 已配置，但性能与兼容性以 GraalVM 为准）。
  - **下载**：[GraalVM 官方下载页](https://www.graalvm.org/downloads)（选 JDK 21 LTS、Windows x64）；旧版归档见 [Oracle GraalVM for JDK 21 Archive](https://www.oracle.com/java/technologies/javase/graalvm-jdk21-archive-downloads.html)。
  - **版本必须与 pom 的 `graalvm-js.version` 匹配**：GraalJS 依赖与 JDK 的 GraalVM compiler 版本绑定（对应 21.0.N ↔ 23.1.N）。当前 pom 用 **23.1.11** ↔ JDK **21.0.11**（compiler 23.1.11）。**升级 JDK 小版本须同步改 pom**（如 21.0.12 ↔ 23.1.12）；用错版本会**版本检查失败或降级解释执行**（详见 `m0-dependency-versions` 记忆）。
- **GraalVM JS 不随 JDK 内置**：自 GraalVM for JDK 21 起，JavaScript 运行时改为独立发行/依赖引入。`domain-script` 模块经 Maven 依赖（`org.graalvm.polyglot:*`）加载。
- 构建：Maven 3.8+、JDK 21。

## 当前状态

仓库骨架已就绪，工程代码尚未开始。逐步按 [ARCHITECTURE.md](ARCHITECTURE.md) 落地。
