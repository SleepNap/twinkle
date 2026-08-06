# M1：协议与 Netty

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第二节（Netty 4 / 客户端网络）、第三节（net-netty / net-packet 模块）、第十节红线 1（v83 协议字节级兼容）、第十一节 M1。
>
> 状态：未开始 ｜ 前置依赖：M0 ｜ 影响模块：`net-netty`、`net-packet`、`bootstrap`、`login`

## 目标

客户端能登录、进图。v83 协议、加解密、RecvOpcode **字节级原样移植**，Netty 作为客户端协议栈（内部通信也复用它）。

**交付**：协议 + Netty + HandlerRegistry + NetworkSession，客户端登录进图。

## 任务清单

### 0. 数据层支撑（M1 前置，已完成）

- [x] 接入 MyBatis-Flex SqlSessionFactory（MybatisFlexBootstrap 非 Spring 装配，`new` 独立实例避免测试冲突）
- [x] V2 迁移建 `accounts`（28 列）/ `characters`（67 列）三方言表（红线 2/3：newmaple 结构兼容）
- [x] Account / Character 实体 + Mapper + Repository 接口（findByName / findByAccount）
- [x] 集成测试：SQLite 迁移 + Flex CRUD 通过

### 1. net-packet（协议层，不依赖业务）

- [x] v83 RecvOpcode 原样移植（字节级兼容，红线 1，值来自公开协议，注释自研）
- [x] SendOpcode / 包结构
- [x] `net.encryption` 加解密原样移植（AesCipher + CustomCipher 自研，字节级对照验证通过）
- [x] PacketCodec：包读取/写入，字节级对齐 v83（小端 + short 长度字符串）
- [x] **HandlerRegistry**：可 register/replace（可替换层，贡献点版本化，红线 13）
- [x] HandlerRegistry 经接口访问，注册表不持有游戏对象具体类（PacketSession 解耦）

### 2. net-netty（IO 层，不依赖业务）

- [x] Netty 4 服务端：客户端 v83 连接接入（V83ServerInitializer + LoginServer）
- [x] HTTP 与游戏 Netty 隔离 EventLoop（红线 4：游戏独立 NioEventLoopGroup，不共享 Micronaut HTTP）
- [x] 内部通信复用同一模块（帧格式 `[帧头 | 消息类型 | 消息ID | 负载]`，M6 用，此处定义帧接口 InternalFrame）
- [x] TCP 长连接 + 心跳（供 coordinator 注册中心，M6 用，此处定义心跳接口 Heartbeat）
- [x] 端到端测试：真实 Netty 服务端 + Socket 客户端，握手 + 加密往返 + 分发全链路通过

### 3. NetworkSession + 登录

- [x] NetworkSession：客户端连接会话（连接状态机 SessionStage、封包分发到 HandlerRegistry，net-netty 实现）
- [x] login 模块：账号校验、选角（LoginService 经 repository 接口读 DB，BCrypt 校验；LoginPacketFactory 构造 v83 响应包）
- [x] 校验包顺序：握手 → 登录 → 选角 → 进图（SessionStage 状态机到 SELECTED，进图留 M2）

### 4. 收发包路径打通

- [x] 完整链路：客户端连入 → 握手 → 登录成功 → 选中角色（LoginFlowE2ETest 端到端验证，Socket 模拟 v83 客户端走完整协议）
- [x] HandlerRegistry 的 register/replace 演示（HandlerRegistryTest 覆盖版本化 + login 注册 4 个贡献点）

## 验收标准

- [ ] 客户端登录成功（v83 客户端接入）——协议链路（握手/加解密/opcode）已由 E2E 字节级验证；真实 v83 客户端接入待客户端环境就绪
- [x] 选中角色（LoginFlowE2ETest 验证 CHARLIST + SERVER_IP）
- [x] 封包收发字节级对齐（E2E 逐字节校验 + 加密往返；parity 录包回放框架已落地，真实录包素材待客户端环境）

## 风险与注意

- **v83 协议字节级兼容**是红线 1：`net.encryption` + `RecvOpcode` 原样移植，禁止"顺手改进"。
- HandlerRegistry 是核心贡献点，从第一天版本化（红线 13）；注册表经接口访问，不引用游戏对象具体类。
- **内存态是权威**：登录校验读 DB，但在线状态等热数据仍在内存。
