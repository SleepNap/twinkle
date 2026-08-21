# Twinkle

**热更新、扩展性好的冒险岛后台**（MapleStory v83 服务端）。

> 完整架构设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。设计决策、模块划分、运行拓扑均以该文档为准。

## 目录结构

```
twinkle/
├── twinkle-server/          # 后端服务（Java 多模块，包根 org.gms）
├── twinkle-web/             # React 19 + Vite + shadcn 运维控制台
├── ARCHITECTURE.md          # 架构定稿
└── LICENSE
```

## 环境要求

- **JDK：必须使用 GraalVM for JDK 21**（架构红线 16）。GraalVM 原生内置 JVMCI；普通 Temurin JDK 跑脚本引擎需额外 `-XX:+EnableJVMCI`（surefire 已配置，但性能与兼容性以 GraalVM 为准）。
  - **下载**：[GraalVM 官方下载页](https://www.graalvm.org/downloads)（选 JDK 21 LTS、Windows x64）；旧版归档见 [Oracle GraalVM for JDK 21 Archive](https://www.oracle.com/java/technologies/javase/graalvm-jdk21-archive-downloads.html)。
  - **版本必须与 pom 的 `graalvm-js.version` 匹配**：GraalJS 依赖与 JDK 的 GraalVM compiler 版本绑定（对应 21.0.N ↔ 23.1.N）。当前 pom 用 **23.1.11** ↔ JDK **21.0.11**（compiler 23.1.11）。**升级 JDK 小版本须同步改 pom**（如 21.0.12 ↔ 23.1.12）；用错版本会**版本检查失败或降级解释执行**（详见 `m0-dependency-versions` 记忆）。
- **GraalVM JS 不随 JDK 内置**：自 GraalVM for JDK 21 起，JavaScript 运行时改为独立发行/依赖引入。`domain-script` 模块经 Maven 依赖（`org.graalvm.polyglot:*`）加载。
- 后端构建：Maven 3.8+、JDK 21。
- Web 构建：Node.js 20+、npm。

## 当前状态

M0–M6 服务端里程碑已完成，当前持续扩展 `/api/v1` 能力面和 Web 运维控制台。

Web 控制台采用 React 19、Vite、Tailwind CSS v4 和 shadcn `radix-nova`，已经接入运行概览、
频道、在线玩家、账号管理、角色权限、配置、运维操作、API Key、能力目录、审计日志和任务监控。账号管理支持
模糊检索、状态筛选、分页、账号管控，以及角色属性、货币、背包、任务、技能和好友快照；API Key 支持
签发、启停、轮换、吊销及 Scope 调整；`ai:use` 可作为 AI 调用总开关即时授予或收回。
后台任务通过统一注册表提供有界执行历史、状态、耗时、失败摘要、调度启停和失败重试，
现有 AI 每日总结是首个接入任务。

详细范围与遗留见 [控制台路线图](docs/in-progress/console-roadmap.md)，视觉与组件纪律见
[Web 设计规范](docs/archived/design/design-system.md)。

## 本地验证

后端：

```bash
cd twinkle-server
mvn -B verify
```

Web：

```bash
cd twinkle-web
npm install
npm run dev
```

Web 开发服务器默认把 `/admin/v1` 和 `/api/v1` 代理到 `127.0.0.1:8080`。正式预览应打开
Vite 地址；`demo.html` 仅保留旧书签兼容用途。
