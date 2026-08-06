---
name: m1-progress
description: M1（协议+Netty+登录）核心完成：交付内容、字节级兼容验证、关键坑与待办
metadata:
  type: project
---

M1 核心完成（2026-08-06）：net-packet 协议层 + data（MyBatis-Flex/accounts/characters）+ net-netty + login + bootstrap 装配 + parity 录包回放框架。全模块测试通过（含 LoginFlowE2ETest 全链路、ArchitectureDependencyTest 架构测试）。

**字节级兼容验证**：AesCipher/CustomCipher 与参考实现逐字节对照（临时脚本不进仓库）；E2E 用 Socket 模拟 v83 客户端走握手→登录→列表→选角，逐字节校验响应包。

**关键决策与坑**（后续 M2 沿用）：
- **HandlerRegistry 版本化**（红线 13）：register/replace 带版本，替换须更高版本。
- **SessionStage 下沉 net-packet**：连接阶段枚举在协议层，handler/IO 层共享，不互相依赖（可替换层纪律）。
- **ArchUnit 规则 4 豁免 login**：login 是 v83 登录协议处理器，必然依赖 net-packet；HTTP/AI 管理 API 仍禁止碰协议栈。
- **MyBatis-Flex 非 Spring 装配**：`new MybatisFlexBootstrap()` 多实例；**Mappers/FlexGlobalConfig 是静态注册表**，测试多实例必须 `setEnvironmentId(唯一值)` 否则 getMapper 拿旧实例指向别的 db。
- **param_conf 已统一 MyBatis-Flex**（2026-08-06）：删除 M0 的 JdbcParamConfRepository，用 FlexParamConfRepository（ParamConfMapper）+ DbConfigFacade 接口不变。业务/测试无 JDBC 绕开残留。
- **配置统一 yml**（2026-08-06）：bootstrap 单份 application.yml（data 不再有配置，避免双份合并歧义）。**Micronaut 4 用 YAML 必须显式加 `org.yaml:snakeyaml`（runtime）**（4.0 起不再是传递依赖）；micronaut-config 4.x artifact 不存在，勿引。
- **Flex*Repository 实现类不要加 @Singleton**：会与 MyBatisFlexFactory 的 @Bean 双份产生「Multiple possible bean candidates」歧义，统一由 Factory 装配。
- **MybatisFlexBootstrap 有 insertSelective**：insert 全字段会传 null 覆盖 DB DEFAULT，NOT NULL 列需 insertSelective。
- **迁移脚本方言节内不可用空行**：MigrationRunner.filterForDialect 约定"空行=节结束回全方言"，节内空行导致后续语句泄漏到所有方言（V2 踩过）。
- **Cipher.getInstance("AES") 默认 PKCS5Padding**：满块补一整个 padding 块，doFinal 返回 17 字节，v83 只用前 16 字节（AesCipher.aesBlock 截断）。

**M1 待办**：真实 v83 客户端接入验收（协议链路已 E2E 验证）；parity 真实录包素材（需客户端环境）。进图留 M2。

相关：[[twinkle-project-context]] [[m0-dependency-versions]]
