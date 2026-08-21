# API 版本、兼容与文档

## 目标

- 公共 API：`/api/vN`。
- Web 控制台 API：`/admin/vN`。
- 内部 API：`/internal/vN`。
- `/api/vN` 与 `/internal/vN` 统一使用 API Key + Scope + 审计；`/admin/vN` 使用管理员会话 + RBAC + 审计。网络位置不能替代应用层鉴权。
- 历史版本的路由、DTO、状态码和错误语义在退役前保持可用，调用方可以同时使用不同主版本。
- 新主版本只分叉发生增删改的端点；未变化端点复用同一 Handler/use-case。
- 每个已发布主版本都提供完整、机器可读的 OpenAPI，不单独维护 Markdown 接口清单。

## 路由与实现

`ApiRoutes` 只定义网络平面与主版本路径。`ApiVersionCatalog` 是已发布版本及生命周期的唯一登记表；新增 Controller 但未登记版本时，请求仍会被版本过滤器拒绝。

未变化端点允许一个 Micronaut 方法通过 `uris` 同时挂载多个主版本：

```java
@Get(uris = {"/api/v1/online", "/api/v2/online"})
public OnlineResponse online() {
    return endpoint.online();
}
```

这只是两个路由绑定，业务实现和响应契约仍只有一份。发生变化时保留 v1 方法不动，新增 v2 Controller/DTO/Mapper；业务规则相同的部分继续复用 `application.*`。

禁止以下做法：

- `V2Controller extends V1Controller`。
- v2 Controller 调用 v1 Controller。
- 把 `/api/v2/**` 通配重写到 `/api/v1/**`。
- 公共 Controller 直接返回 data entity/repository 类型。
- 为了主版本升级复制 application/service 业务实现。

## 代码分层

1. `api.vN.controller`：只做 HTTP 参数绑定、状态码和 header，不直接组装动态 Map。
2. `api.vN.dto.request|response|error`：保存该版本冻结的传输对象，也是 OpenAPI schema 的代码真值。
3. `api.vN.mapper`：在版本无关的 application/service 结果与该版本 DTO 之间转换。
4. `api.vN.contract`：保存版本常量以及依赖 HTTP 框架的契约响应工厂。
5. `api.stable.endpoint/contract`：被多个主版本共同使用、不可原地修改的 HTTP 行为。
6. `application.*`：与 HTTP 版本无关的用例编排和只读投影。
7. `auth`、`admin`：按版本相对路径解析的认证、授权、限流和审计。
8. `data` 与 core service：持久化和稳定业务接口，不得成为公共传输契约。

## OpenAPI 与文档

项目同时采用两种生成路径，但同一网络平面只能有一个发布真值：

- 公共第三方 API：`openapi/public/vN/openapi.yaml` 是已经审核并冻结的发布契约。代码生成的实际路由 OpenAPI用于差异检查，发布时二者必须一致。
- 管理/内部 API：Micronaut Controller/DTO 是真值，`micronaut-openapi` 在编译期生成 OpenAPI。

生成产物通过 `/api-docs/**` 暴露，Swagger UI 通过 `/docs/**` 浏览。第三方仍使用稳定的 `/api/vN/openapi.yaml` 下载对应主版本契约，可以据此生成 SDK。

以后发布 v2 时，v2 契约对第三方必须是完整文件。仓库内部可以从 v1 契约组合未变化的 Path/Schema，再覆盖 v2 差异；发布产物不得要求第三方理解内部继承关系。

## 生命周期

每个网络平面的主版本状态为：

```text
ACTIVE → DEPRECATED → RETIRED → 删除
```

- `ACTIVE`：正常支持。
- `DEPRECATED`：继续完整服务，同时返回 `Deprecation`、可选 `Sunset` 与迁移指南 `Link`。
- `RETIRED`：路由统一返回 `410 Gone`，保留迁移提示。
- 删除：移除版本登记、Controller、contract 和 OpenAPI，未知版本返回 `404`。

退役前必须按版本观察调用量，确认 Web 与第三方已经迁移。共享 Handler 若仍被新版本使用，只删除历史版本路由和 DTO，不删除共享实现。

## 自动门禁

- 所有 HTTP Controller 必须位于版本包或明确的稳定 endpoint 包。
- 公共 Controller 不得依赖持久化实体/仓库。
- 已发布 OpenAPI 与实际路由进行差异检查。
- v1 契约快照发生破坏性变化时构建失败；新增 v2 不得修改 v1 快照。
- 每个已登记版本的路由必须恰好绑定一次，防止漏挂或冲突。
