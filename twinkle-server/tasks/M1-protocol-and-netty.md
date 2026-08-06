# M1：协议与 Netty

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第二节（Netty 4 / 客户端网络）、第三节（net-netty / net-packet 模块）、第十节红线 1（v83 协议字节级兼容）、第十一节 M1。
>
> 状态：未开始 ｜ 前置依赖：M0 ｜ 影响模块：`net-netty`、`net-packet`、`bootstrap`、`login`

## 目标

客户端能登录、进图。v83 协议、加解密、RecvOpcode **字节级原样移植**，Netty 作为客户端协议栈（内部通信也复用它）。

**交付**：协议 + Netty + HandlerRegistry + NetworkSession，客户端登录进图。

## 任务清单

### 1. net-packet（协议层，不依赖业务）

- [ ] v83 RecvOpcode 原样移植（字节级兼容，红线 1）
- [ ] SendOpcode / 包结构
- [ ] `net.encryption` 加解密原样移植（v83 协议字节级兼容，红线 1）
- [ ] PacketCodec：包读取/写入，字节级对齐 v83
- [ ] **HandlerRegistry**：可 register/replace（可替换层，贡献点从第一天版本化，红线 13）
- [ ] HandlerRegistry 经接口访问，注册表不持有游戏对象具体类

### 2. net-netty（IO 层，不依赖业务）

- [ ] Netty 4 服务端：客户端 v83 连接接入
- [ ] HTTP 与游戏 Netty 隔离 EventLoop（红线 4，M0 预留此处落地）
- [ ] 内部通信复用同一模块（帧格式 `[帧头 | 消息类型 | 消息ID | 负载]`，M6 用，此处定义帧接口）
- [ ] TCP 长连接 + 心跳（供 coordinator 注册中心，M6 用，此处定义心跳接口）

### 3. NetworkSession + 登录

- [ ] NetworkSession：客户端连接会话（连接状态机、封包分发到 HandlerRegistry）
- [ ] login 模块：账号校验、选角（读 DB，经 service 接口）
- [ ] 校验包顺序：握手 → 登录 → 选角 → 进图（进图留到 M2 的 world/channel 初版，此处打通到"选中角色"）

### 4. 收发包路径打通

- [ ] 完整链路：客户端连入 → 握手 → 登录成功 → 选中角色
- [ ] HandlerRegistry 的 register/replace 演示（验证可替换）

## 验收标准

- [ ] 客户端登录成功（v83 客户端接入，握手/加解密/opcode 对齐）
- [ ] 选中角色
- [ ] 封包收发字节级对齐参考项目（用 M0 的 parity 录包回放基建比对）

## 风险与注意

- **v83 协议字节级兼容**是红线 1：`net.encryption` + `RecvOpcode` 原样移植，禁止"顺手改进"。
- HandlerRegistry 是核心贡献点，从第一天版本化（红线 13）；注册表经接口访问，不引用游戏对象具体类。
- **内存态是权威**：登录校验读 DB，但在线状态等热数据仍在内存。
