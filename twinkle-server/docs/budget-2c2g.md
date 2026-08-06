# 2C2G 内存/启动预算基线（M0 第 7 项）

> 架构第九节：2C2G 能跑是硬指标。本文件记录 M0 校准的预算基线，作为后续每个里程碑的回归对照。

## 内存预算（2G 总账，架构 9.1）

```
2G 总内存预算：
  堆 1G           游戏对象、状态、业务
  堆外 256M       Netty 直接内存、WZ 缓存
  原生内存 512M   GraalVM JS 引擎、JIT、语言运行时
  系统/OS 余量 256M
```

## M0 实际组件占用（校准值，2026-08）

| 组件 | 预算 | M0 实测 | 说明 |
|---|---|---|---|
| JVM 基座 | — | ~150-200M（含 Code Cache + 元空间） | Micronaut 启动后空闲态 |
| SQLite | 归零（嵌入式） | ~0（进程内） | 低配默认零常驻 |
| Micronaut DI/上下文 | — | ~50M | 编译时 DI，启动快 |
| GraalVM JS 引擎 | 512M 预算内 | ~80-120M（含 JIT 编译产物） | `-XX:+EnableJVMCI` 全速 |
| 业务（param_conf 索引等） | — | <10M | M0 极简 |

## JVM 启动参数（单进程档）

```
-Xms512m -Xmx1g                 # 堆上限 1G（收紧堆，红线 9.1）
-XX:+UseSerialGC                # 2 核 CPU 用 SerialGC（单线程 GC，省线程）
-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI   # GraalVM JS 全速（红线 16）
-XX:+TieredCompilation          # 默认即可
-XX:+AlwaysPreTouch             # 启动即分配，避免运行时卡顿（可选）
```

## 启动耗时基线（2C2G 目标）

| 阶段 | 目标 | 说明 |
|---|---|---|
| JVM 启动 + Micronaut | < 2s | 编译时 DI，无反射扫描 |
| 数据源 + 迁移 | < 1s | V1 单迁移，毫秒级 |
| GraalVM JS 引擎 | ~200ms | 首次创建 Context |
| **全启动** | **< 5s** | 不含 WZ 加载（WZ 缓存生效后达标） |

## WZ 文件缓存（架构 9.3）

WZ 预编译到磁盘缓存（序列化），启动读缓存文件省重解析。这是 2C2G 上"秒级重开"的关键。
M0 落占位（wz-provider 模块），M2 实现。

## 如何验证（M0 验收项）

```bash
# 内存
mvn -q package -DskipTests
java -Xms512m -Xmx1g -XX:+UseSerialGC -jar bootstrap/target/twinkle-bootstrap-*.jar --profile=single
# 观察 RSS（Windows: 任务管理器 / PowerShell Get-Process）
# 启动耗时
# 记录从 java 启动到 "Twinkle started" 日志的时间

# 2C2G 严格验证
# 用 Docker: docker run -m 2g --cpus 2 --memory 2g <image>
```

## 红线对照

- **2C2G = 强制单进程**（红线 15）：任何 `split-*` 档在 2G 内存下被装配层拒绝。
- **不默认引入重服务**：缓存/对象/MQ 不默认引入（红线 9.1），进程内实现 + 接口打底。
- **池化库不默认**：SQLite 用 `SimpleDriverDataSource`（DriverManager），大服档再按需加 HikariCP。
