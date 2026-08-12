# Twinkle Web 设计与组件规范

> 状态：已定稿（2026-08-12 用户拍板）
>
> 技术栈：React 19 + Vite + TypeScript + Tailwind CSS v4 + shadcn
>
> 视觉基准：shadcn 官方 `radix-nova` preset

## 1. 核心决策

Twinkle 以 shadcn 自身的组件风格、默认密度和语义令牌为视觉权威，不再模仿 Notion、
Ant Design 或其他品牌设计系统。

- 组件通过 shadcn CLI/Registry 引入，源码落在 `src/components/ui/`。
- `components.json` 固定使用 `radix-nova`、Radix、Lucide 和 CSS Variables。
- `src/index.css` 保留 CLI 生成的 neutral 浅色/深色主题。
- 页面优先组合官方组件、尺寸和 variant，不另造同类基础组件。
- 业务代码不得覆盖 shadcn 组件内部选择器，不使用 `!important` 修补视觉差异。

## 2. 组件边界

### 基础组件

`src/components/ui/` 只存放 shadcn Registry 组件。当前包括：

- Button
- Badge
- Card
- Dialog
- Input
- Table
- Alert
- Separator
- Skeleton
- Label
- Sonner
- Checkbox
- Dropdown Menu
- Tabs

新增组件使用：

```bash
npx shadcn add <component>
```

### 业务组件

频道状态、玩家搜索、Agent 审计等业务组合组件放在 `src/components/` 的业务目录中。
业务组件可以组合基础组件，但不得重新实现 Button、Dialog、Input、Table 等已有能力。

## 3. 样式纪律

1. 优先使用组件的官方 `variant`、`size` 和状态属性。
2. 页面级 Tailwind 只负责布局、响应式、间距和必要的排版。
3. 颜色使用 `background`、`foreground`、`primary`、`secondary`、`muted`、
   `accent`、`destructive` 等 shadcn 语义令牌，不写品牌仿制色。
4. 圆角、边框、焦点环和深色主题以 `src/index.css` 的官方 preset 为准。
5. 不直接修改 `src/components/ui/` 来满足单个页面的视觉偏好；优先在业务组合层解决。
6. 如确需修改基础组件，必须记录原因，并先查看上游差异：

```bash
npx shadcn add <component> --diff
```

## 4. 一致性与升级

- `components.json` 是生成配置真值，`src/index.css` 是主题真值。
- 不无条件执行 `--overwrite`；更新组件前先查看 diff。
- shadcn 组件更新与 Radix、React、Tailwind 依赖升级分开处理和验证。
- 提交前必须执行：

```bash
npm run build
npm run lint
npm test
npm run typecheck
```

## 5. 当前落地

- React/Vite/Tailwind v4 工程已建立。
- shadcn `radix-nova` 已正式初始化。
- 当前基础组件均由 shadcn Registry 生成，新增能力继续执行 `add --diff` 纪律。
- 已建立 React Router 控制台框架和 TanStack Query 数据层。
- 已完成运行概览、频道、在线玩家、账号角色、配置中心、运维操作、API Key、审计日志页面。
- 页面具备自动轮询、手动刷新、骨架屏、失败重试和空状态；不使用伪造业务数据兜底。
- 配置热改、踢下线、脚本/逻辑重载和重启均使用确认对话框、防重复提交与统一 Toast 反馈。
- 管理 API 客户端已建立 Vitest 契约测试，覆盖读取、写入、错误码、断网和非 JSON 响应。
- `demo.html` 仅作为旧书签兼容入口，正式预览须通过 `npm run dev` 打开 Vite 应用。

## 6. 国际化纪律

- 全部用户可见文案使用 `src/i18n/` 中的稳定 message key，页面不得新增硬编码中文或英文。
- 首批支持 `zh-CN`、`en-US`；用户选择写入 `localStorage` 的 `twinkle.locale`，同时更新根节点 `lang`。
- Web UI locale 独立于 Java 服务端语言；服务端响应使用 `Content-Language` 表明其固定配置语言。
- 数字、日期和时间必须使用当前 locale 的 `Intl` formatter；错误码、配置键、角色 ID、opcode 等机器标识不翻译。
- 添加 key 时必须同时补齐所有受支持语言，TypeScript 以 `zh-CN` 目录键集合执行编译期约束。
