# twinkle 运维控制台 · 设计系统

> 状态：**已定稿（2026-08-09 用户拍板）**
> 技术栈：React 19 + Vite + TypeScript + shadcn/ui + Tailwind CSS
> 设计系统：**Notion 中性灰主基调**

---

## 1. 设计哲学

| 原则 | 说明 |
|------|------|
| **内容优先** | 数据是主角，界面退到背景。大量留白，低视觉噪声。 |
| **中性主基调** | 界面主色调全部为灰阶（暖灰文字 + 发丝灰边框）。**没有大面积彩色**。 |
| **克制用色** | 蓝 (`--primary` #2383E2) **仅用于主操作按钮 / 链接**；绿 / 黄 / 红 **仅用于数据语义状态**（在线 / 异常 / 离线），不计入主基调。 |
| **浅深双主题** | 浅色为主，深色为暖黑 `#191919`（非纯黑），通过 CSS 变量零改组件切换。 |
| **令牌驱动** | 所有颜色走 shadcn 约定的 CSS 变量，组件不写死色值，主题切换只换变量。 |

> ⚠️ 反例：不要把侧栏选中态、卡片图标、logo 染成蓝色——那是"蓝色 SaaS 后台"而非 Notion。蓝只在用户要"点下去"的地方出现。

---

## 2. 设计令牌（节选，完整见 `src/styles/tokens.css`）

| 令牌 | 浅色 | 深色 | 用途 / 对比度 |
|------|------|------|------|
| `--background` | `0 0% 100%` | `0 0% 10%` | 页面背景 |
| `--foreground` | `40 8% 20%` (#37352F) | `0 0% 90%` | 正文主色，对白 ≈ 11:1 (AAA) |
| `--muted` | `60 8% 96%` (#F7F7F5) | `0 0% 13%` | 侧栏 / 分组底 |
| `--muted-foreground` | `40 5% 42%` | `0 0% 55%` | 次级文字，对白 ≈ 4.6:1 (AA) |
| `--border` | `60 6% 91%` (#E9E9E7) | `0 0% 18%` | 发丝分隔线 |
| `--primary` | `207 76% 51%` | 同浅 | **仅主操作 / 链接** |
| `--success` | `173 79% 27%` | `160 45% 50%` | 在线 |
| `--warning` | `45 99% 44%` | `45 99% 50%` | 异常（底色 / 图标） |
| `--danger` | `0 76% 58%` | `0 76% 66%` | 离线 / 错误 |
| `--radius` | `0.5rem` | 同浅 | Notion 偏直角 |

---

## 3. 核心组件规格

### 3.1 Button
| 变体 | 样式 | 用途 |
|------|------|------|
| **主 (primary)** | `bg-primary text-primary-foreground` | 唯一允许出现蓝的地方：保存配置、确认执行 |
| **次 (secondary)** | `bg-secondary text-secondary-foreground border` | 常规操作 |
| **危险 (danger)** | `bg-danger text-white` | 危险确认（停服 / 踢人） |
| **幽灵 (ghost)** | `hover:bg-muted` 透明底 | 工具栏图标按钮 |

圆角 `--radius`；focus 用 `ring-2 ring-ring`（中性灰，非蓝）。

### 3.2 状态标签 Badge（数据语义）
胶囊形，淡底实字 + 小圆点：
- 在线：`bg-success/14 text-success`
- 异常：`bg-warning/16 text-[#9A6700]`（深一档保 AA；深肤下用 `--warning`）
- 离线：`bg-danger/14 text-danger`

### 3.3 Table（高密度数据表）
- 表头：`text-muted-foreground text-xs uppercase`，发丝 `--border` 底分隔
- 行高约 40px，行间 1px `--border` 分隔（不用斑马纹）
- 行 hover：`bg-foreground/[0.03]`
- 数字列用 `font-mono text-muted-foreground`

### 3.4 Card / 面板
`bg-card border border-border rounded-lg`，默认无阴影；浮层（Dialog / Popover）才用阴影。

### 3.5 Dialog
基于 shadcn `Dialog`（Radix）。危险操作（如停服）需在标题区加 `bg-danger/10 text-danger` 提示条。

### 3.6 导航（侧栏）
- 分组 + 图标 + 文字；active 态：`bg-foreground/7 text-foreground font-medium`（**浅灰底 + 深色粗字，不染蓝**）
- 角标（如待处理告警数）：`bg-danger/15 text-danger` 胶囊

### 3.7 主题切换
- 三态：浅 / 深 / 跟随系统
- 状态持久化 `localStorage['twinkle-theme']`，写到 `<html class="dark">`
- 首次访问读 `prefers-color-scheme`

---

## 4. 可访问性（WCAG AA）

- 正文对比度 ≥ 4.5:1，大字 ≥ 3:1（已按 `DESIGN-CANDIDATES.md` 评审收紧）
- 交互元素触控目标 ≥ 44px
- 全键盘可达，focus 可见（中性灰 ring）
- 尊重 `prefers-reduced-motion`

---

## 5. 与 shadcn/ui 的关系

shadcn/ui **不是 npm 包**，而是把组件源码拷贝进 `src/components/ui/`，由本项目令牌驱动。
落地步骤：
1. `npx shadcn@latest init`（读 `tailwind.config.ts` + `tokens.css`）
2. `npx shadcn@latest add button table card dialog badge input`（按需）
3. 组件默认即吃 Notion 令牌，无需逐个改样式。

---

## 6. 落地说明

- 本目录 `twinkle-web/` 目前为**设计层文件**（`tokens.css` / `tailwind.config.ts` / 本档），**尚未 init 脚手架**（无 `package.json` / `vite.config`）。
- 脚手架与页面实现按 `ARCHITECTURE.md` 的 **M5 里程碑**推进，业务接口就绪后再写页面。
- 视觉参考：`twinkle-web/demo.html`（已确认的中性灰主基调预览）。
