# Twinkle 新人上手指南（30 分钟版）

> 完整设计见 `ARCHITECTURE.md`（38KB，架构师必读）。本文是"先跑起来、知道改哪"的最小路径。

## 1. 这是什么
Twinkle 是**冒险岛 v83 私服服务端**（Java 21 / Micronaut 4）。目标：热更新、扩展性好。参考项目：北斗（BeiDou-Server）。
当前里程碑 **M0**（骨架 + 基础验证 + 热更新地基），业务代码起步中。

## 2. 环境
- JDK：**Temurin 21**（强制，红线 16；GraalVM JS 需 JVMCI）
- 构建：Maven 3.8+（仓库无 wrapper，本地自行安装 Maven）
- IDE：IntelliJ IDEA + Micronaut 插件

## 3. 构建 & 跑测试
```bash
cd twinkle-server
mvn -B verify          # 编译 + ArchUnit 架构守护 + 单测/集成/E2E
```
> 架构守护测试 `ArchitectureDependencyTest` 在 `bootstrap` 模块，会**强制模块依赖方向**——改错依赖方向会直接让 `verify` 失败。这是护栏，不是负担。

## 4. 模块怎么看（按角色）
- **公共底座**（所有进程都要）：`bootstrap`(唯一 main，读 `--profile` 装配) · `core`(DI/EventBus/配置/热更新) · `net-netty`/`net-packet`(v83 协议) · `data`(MyBatis-Flex+自研迁移) · `db-dialect`(方言) · `plugin-api`
- **游戏域**（频道进程）：`domain-game`(游戏对象) · `domain-script`(GraalVM JS) · `wz-provider` · `channel`
- **管理侧**（管理进程）：`coordinator` · `login` · `admin` · `http-api` · `ai`

**铁律**：管理侧**不得**依赖 `domain-game`；一个 JVM 按 `--profile` 装配一组模块，进程边界是配置不是硬编码。

## 5. 改一个需求从哪入手（示例：登录校验）
1. 协议入口：`net-packet`（opcode / `PacketHandler` / `HandlerRegistry`）
2. 登录流程：`login`（`LoginService` / `handler/*`）→ 读 DB 经 `data` 的 repository 接口
3. 业务规则：放在 `login` 的 Service，**不堆在 Handler**
4. 加单测：参照 `login/src/test/.../LoginServiceTest`

## 6. 铁律速记（完整见 ARCHITECTURE 三条铁律）
1. **一套代码多种拓扑**：进程边界是配置不是硬编码，接口不假设进程内。判据：`standalone 下 X 是什么、分布式下 X 变什么？`
2. **性能硬指标**：2C2G 单进程必须能跑，禁默认引入常驻大内存/重服务。
3. **状态与逻辑分离**：游戏实体 = 纯数据 + 操作数据的逻辑系统，这是热重载/分布式/插件的共同地基。

## 7. 遇到架构约束怎么办
- 拿不准依赖方向 → 先想"standalone 下 X 是什么、分布式下 X 变什么"。
- 加新逻辑系统 → 走可替换层（经接口访问稳定层），别碰稳定层具体类（换 classloader 会 CCE）。
- 任何改动先 `mvn -B verify` 全绿再提 PR，PR 模板的红线清单逐条过。
- 设计疑问 → 读 `ARCHITECTURE.md` 对应章节；仍不确定 → 找架构作者确认，**别猜**。

## 8. 必读顺序
`CLAUDE.md`（红线）→ 本文 → `ARCHITECTURE.md` 对应章节 → 代码（从 `bootstrap` / `login` 入手）。
