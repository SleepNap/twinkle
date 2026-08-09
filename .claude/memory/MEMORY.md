# Memory Index

- [Reference projects discipline](reference-projects-discipline.md) — 参考北斗/datas-server 时的 GPL/MIT 许可纪律：禁止抄袭、尽量优化、引用归因、以北斗为主
- [Twinkle project context](twinkle-project-context.md) — twinkle 项目背景：架构以 ARCHITECTURE.md 为准、任务拆在 twinkle-server/tasks/、关键红线
- [M0 dependency versions](m0-dependency-versions.md) — M0 经 Maven Central 核证的最新依赖版本（Micronaut 4.10/JDK21、Netty 4.2.17、MyBatis-Flex 1.11.8 等）与兼容性事实
- [M1 progress](m1-progress.md) — M1（协议+Netty+登录）核心完成：字节级验证方式、HandlerRegistry/SessionStage 决策、MyBatis-Flex 静态注册表等关键坑、真实客户端验收待办
- [M2 progress](m2-progress.md) — M2（游戏逻辑重写）进图打通：SET_FIELD/addCharacterInfo 字节结构、MyBatis-Flex 驼峰列名/insertSelective 覆盖 DEFAULT 等坑、频道初版决策与待办
- [DB naming & migration standard](db-naming-migration-standard.md) — 数据库命名与迁移规范（表≥2词、snake_case、禁关键字、common/sqlite/pg/mysql 目录、禁 -- dialect 节），用户强制要求
