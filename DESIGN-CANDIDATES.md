# twinkle 运维控制台 · 设计系统候选方案（Phase 2）

> **历史材料，已废止**：2026-08-12 用户最终决定全面采用 shadcn `radix-nova`
> 官方风格，不再从本候选文档选取或混合 Notion、Apple、Linear 视觉令牌。
> 当前权威规范见 `twinkle-web/docs/design-system.md`。

> 设计系统专家：彩格调（Cai）
> 阶段目标：从 71 套内置品牌级设计系统中，为「twinkle 服务端 Web 控制台 / 运维管理后台仪表盘」推荐 2–3 套最贴合 **简约专业 / Apple-Notion** 方向的候选，并产出可对比的定制设计令牌草案。
> **门禁说明**：以下方案仅供老板（用户）拍板。未经明确批准，不得进入 Phase 3 原型生成。
> **评审修订（2025-08-06 · 严过审）**：按评审意见收紧令牌层 WCAG AA 对比度——① 三套 `--color-text-tertiary` 浅色提至 ≥0.55、深色 ≥0.50（实测浅≈3.3–4.8:1、深≈4.6–5.2:1，达 AA）；② 新增 `--color-warning-text`（#9A6700，浅底≈4.9:1）专用于 warning 作文字场景，原 `--color-warning` 仅保留给图标/底色/徽标填充。详情见各系统令牌内联注释与文末修订明细。

---

## 一、候选方案对比总表

| 方案 | 设计系统 | 类别 | 匹配度 | 核心特征（与 Apple/Notion 简约专业调性的契合点） | 与「游戏服务端运维后台」的适配性 |
|------|---------|------|--------|------|------|
| **A** | **Apple（Human Interface）** | 其他/通用 | ★★★★★ | 系统字体、极致克制、大量留白、靠分隔线而非阴影构建层级、浅/深双主题原生 | 视觉抛光标杆；系统字体栈零加载、跨平台一致；浅色清爽、深色成熟（macOS/iOS dark）。但默认偏「松」，需为仪表盘适度收紧密度 |
| **B** | **Notion** | 生产力 | ★★★★☆ | 内容优先、中性暖灰（#37352F）、侧边栏导航、表格友好、克制到近乎隐形 | **最贴「多区块总览仪表盘 + 左侧/顶部锚点导航 + 玩家管理表格 / 配置中心」**；表格与区块是 Notion 的强项；浅深双主题官方支持；视觉噪声极低，数据即主角 |
| **C** | **Linear** | 开发工具 | ★★★★☆ | 开发者工具美学、Inter 字体、深色优先、状态色体系成熟（issue states）、信息密度高 | **最贴「技术受众 + 集群健康 / 告警 / 在线-离线状态监控」**；状态标签与危险确认交互是 Linear 的拿手好戏；深色原生、半透明层级清晰；密度高于 Apple/Notion，适合长时间高频停留 |

> 三套均为 71 套库中的真实系统，且互相差异化：Apple=抛光/留白、Notion=内容/表格/侧栏、Linear=运维/状态/深色密度。
> 若用户想要「最安全无风格」的兜底，可补 **Default (Neutral Modern)** 作为第四备选（同属通用起始类）。

---

## 二、候选 A — Apple（Human Interface）

### 为何贴合
Apple HIG 是「简约专业」的教科书：靠系统字体、留白、发丝级分隔线和语义色传达层级，几乎不用装饰阴影。浅色为主、深色为系统级一等公民。

### 适配性说明
- 系统字体栈（`-apple-system` / `BlinkMacSystemFont`）零网络加载、跨 macOS/Windows/移动端表现一致——对长时间停留的运维后台友好。
- 语义色（System Blue/Green/Red/Orange）正好映射「在线/异常/离线/警告」。
- 注意：Apple 默认偏宽松，仪表盘需把间距与字号往下收一档（见令牌注释）。

### 设计令牌草案（CSS 变量）

```css
/* ===== Apple · 浅色（默认） ===== */
:root {
  /* —— 表面 / 背景 —— */
  --color-bg: #FFFFFF;
  --color-bg-grouped: #F2F2F7;          /* 区块/分组背景 */
  --color-surface: #FFFFFF;
  --color-surface-secondary: #F2F2F7;

  /* —— 文本（标签色阶，含透明度分层） —— */
  --color-text-primary: #000000;
  --color-text-secondary: rgba(60,60,67,0.60);
  --color-text-tertiary: rgba(60,60,67,0.58);  /* 评审收紧：原 0.30≈2.0:1 → 现 0.58≈3.3:1（达大字 3:1；小号正文请用 secondary） */

  /* —— 边框（发丝分隔线） —— */
  --color-border: rgba(60,60,67,0.29);

  /* —— 语义色（iOS 系统色，真实值） —— */
  --color-primary: #007AFF;             /* System Blue */
  --color-primary-hover: #0066D6;
  --color-success: #34C759;             /* System Green */
  --color-warning: #FF9500;             /* System Orange，仅用于非文字（图标/底色/徽标填充） */
  --color-warning-text: #9A6700;        /* 评审新增：warning 作文字对白底≈4.9:1（原 #FF9500≈2.2:1 不达标） */
  --color-danger: #FF3B30;              /* System Red */
  --color-info: #007AFF;

  /* —— 排版 —— */
  --font-sans: -apple-system, BlinkMacSystemFont, "SF Pro Text", "SF Pro Display", "Helvetica Neue", Arial, sans-serif;
  --font-mono: "SF Mono", ui-monospace, Menlo, Monaco, Consolas, monospace;
  --text-xs: 12px; --text-sm: 13px; --text-base: 15px; --text-md: 17px;
  --text-lg: 20px; --text-xl: 24px; --text-2xl: 28px; --text-3xl: 34px;
  --weight-regular: 400; --weight-medium: 500; --weight-semibold: 600; --weight-bold: 700;
  --leading-tight: 1.3; --leading-base: 1.45;

  /* —— 间距（4px 基准） —— */
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-5: 20px; --space-6: 24px; --space-8: 32px; --space-10: 40px; --space-12: 48px;

  /* —— 圆角 —— */
  --radius-sm: 8px; --radius-md: 10px; --radius-lg: 14px; --radius-pill: 999px;

  /* —— 阴影（极克制，优先用分隔线） —— */
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.04);
  --shadow-md: 0 4px 16px rgba(0,0,0,0.06);
  --shadow-lg: 0 12px 32px rgba(0,0,0,0.10);
}

/* ===== Apple · 深色（预留扩展位） ===== */
[data-theme="dark"] {
  --color-bg: #000000;
  --color-bg-grouped: #1C1C1E;
  --color-surface: #1C1C1E;
  --color-surface-secondary: #2C2C2E;
  --color-border: rgba(84,84,88,0.65);
  --color-text-primary: #FFFFFF;
  --color-text-secondary: rgba(235,235,245,0.60);
  --color-text-tertiary: rgba(235,235,245,0.50);  /* 评审收紧：原 0.30 → 现 0.50≈4.6:1（达正文 4.5:1） */
  --color-primary: #0A84FF;             /* 深色下更亮的蓝 */
  --color-success: #30D158;
  --color-warning: #FF9F0A;
  --color-warning-text: #FFB340;        /* 深底下的 warning 文字（亮一档保对比度） */
  --color-danger: #FF453A;
}
```

**关键组件规范（Apple）**
- **按钮·主**：`background: var(--color-primary); color:#fff; border-radius: var(--radius-md); padding: 8px 16px;` hover → `--color-primary-hover`。
- **按钮·次**：`background: var(--color-bg-grouped); color: var(--color-primary); border:none;` 或透明底 + 1px `--color-border`。
- **按钮·危险**：`background: var(--color-danger); color:#fff;`。
- **表格**：行用 1px 发丝分隔线（`var(--color-border)`），**不用斑马纹**；表头 `--text-secondary` 13px medium；行高约 40px。
- **卡片**：`background: var(--color-surface); border:1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-5);` 默认无阴影，悬浮弹层才用 `--shadow-md`。
- **状态标签（在线/异常/离线）**：胶囊形（pill），底色为语义色 12% 透明、文字用实色——
  - 在线/正常：`background: rgba(52,199,89,0.12); color:#1A8A3A;`
  - 异常/警告：`background: rgba(255,149,0,0.12); color:#9A6700;`  /* 文字用 warning-text 层级 */
  - 离线/错误：`background: rgba(255,59,48,0.12); color:#C0342C;`

---

## 三、候选 B — Notion

### 为何贴合
Notion 的设计哲学就是「内容即界面」：中性暖灰文字 `#37352F`、近乎隐形的边框、侧边栏区块导航、表格原生友好。这正是需求中「大量留白、克制视觉语言、清晰信息层级、数据本身即主角」的写照。

### 适配性说明
- **左侧/顶部锚点导航** ↔ Notion 侧边栏结构天然契合。
- **玩家管理表格 / 配置中心（param_conf 热改）** ↔ 表格与属性编辑是 Notion 的强项。
- 浅/深双主题官方支持；视觉噪声极低，适合「数据可读性第一」。
- 默认正文 16px 偏松，仪表盘建议把 base 收到 14px（令牌已收）。

### 设计令牌草案（CSS 变量）

```css
/* ===== Notion · 浅色（默认） ===== */
:root {
  /* —— 表面 / 背景 —— */
  --color-bg: #FFFFFF;
  --color-bg-secondary: #F7F7F5;        /* rgb(247,247,245) 侧栏/分组 */
  --color-surface: #FFFFFF;
  --color-surface-hover: rgba(55,53,47,0.06);

  /* —— 文本（Notion 标志性暖灰） —— */
  --color-text-primary: #37352F;        /* rgb(55,53,47) */
  --color-text-secondary: rgba(55,53,47,0.65);
  --color-text-tertiary: rgba(55,53,47,0.60);  /* 评审收紧：原 0.40≈2.2:1 → 现 0.60≈3.65:1（达大字 3:1；小号正文请用 secondary 0.65≈4.2:1） */

  /* —— 边框 —— */
  --color-border: #E9E9E7;              /* ≈ rgba(55,53,47,0.09) */

  /* —— 语义色（Notion 标签色，真实值） —— */
  --color-primary: #2383E2;             /* 链接/主操作蓝 rgb(35,131,226) */
  --color-primary-hover: #1A6FC0;
  --color-success: #0F7B6C;
  --color-warning: #DFAB01;             /* 仅用于非文字（图标/底色/徽标填充） */
  --color-warning-text: #9A6700;        /* 评审新增：warning 作文字专色，对白底≈4.9:1（评审示例 #B7791F≈3.6:1 仅达大字/加粗 3:1，此处取更深值保小号正文 4.5:1） */
  --color-danger: #E03E3E;
  --color-info: #2383E2;
  --color-purple: #6940A5;              /* Notion 标签紫，可选 */

  /* —— 排版 —— */
  --font-sans: ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, "Apple Color Emoji", Arial, sans-serif;
  --font-mono: "SFMono-Regular", Menlo, Consolas, "Liberation Mono", monospace;
  --text-xs: 12px; --text-sm: 13px; --text-base: 14px; --text-md: 15px;  /* base 由 16 收至 14 以增密度 */
  --text-lg: 16px; --text-xl: 18px; --text-2xl: 22px; --text-3xl: 28px;
  --weight-regular: 400; --weight-medium: 500; --weight-semibold: 600; --weight-bold: 700;
  --leading-tight: 1.35; --leading-base: 1.5;

  /* —— 间距（4px 基准） —— */
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-5: 20px; --space-6: 24px; --space-8: 32px; --space-10: 40px; --space-12: 48px;

  /* —— 圆角（Notion 偏直角） —— */
  --radius-sm: 3px; --radius-md: 6px; --radius-lg: 8px; --radius-pill: 999px;

  /* —— 阴影（几乎不用，仅浮层） —— */
  --shadow-sm: 0 1px 2px rgba(15,15,15,0.10);
  --shadow-md: 0 4px 12px rgba(15,15,15,0.12);
  --shadow-lg: 0 8px 24px rgba(15,15,15,0.16);
}

/* ===== Notion · 深色（预留扩展位） ===== */
[data-theme="dark"] {
  --color-bg: #191919;                  /* Notion 深色主背景 */
  --color-bg-secondary: #202020;
  --color-surface: #202020;
  --color-surface-hover: rgba(255,255,255,0.06);
  --color-border: #2F2F2F;
  --color-text-primary: rgba(255,255,255,0.90);
  --color-text-secondary: rgba(255,255,255,0.55);
  --color-text-tertiary: rgba(255,255,255,0.50);  /* 评审收紧：原 0.35≈3.2:1 → 现 0.50≈5.2:1（达正文 4.5:1） */
  --color-primary: #2383E2;
  --color-primary-hover: #4A9BE8;
  --color-success: #4CB782;
  --color-warning: #DFAB01;
  --color-warning-text: #E0A93B;        /* 深底下的 warning 文字（亮一档保对比度） */
  --color-danger: #FF7369;
}
```

**关键组件规范（Notion）**
- **按钮·主**：`background: var(--color-primary); color:#fff; border-radius: var(--radius-sm); padding: 6px 12px;` hover → `--color-primary-hover`。
- **按钮·次**：`background: transparent; border:1px solid var(--color-border); color: var(--color-text-primary);` hover → `--color-surface-hover`。
- **按钮·危险**：`background: var(--color-danger); color:#fff;`。
- **表格**：表头 `background: var(--color-bg-secondary)`，单元格 1px `--color-border` 网格线（或仅行分隔），行高 36px，行 hover `rgba(55,53,47,0.04)`。
- **卡片**：`background: var(--color-surface); border:1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4);`。
- **状态标签（在线/异常/离线）**：胶囊 + 淡底实字（Notion tag 风格）——
  - 在线/正常：`background: rgba(15,123,108,0.14); color:#0F7B6C;`
  - 异常/警告：`background: rgba(223,171,1,0.16); color:#9A6700;`  /* 文字用 warning-text 层级（配置中心 dirty-flag 等文字场景统一用 --color-warning-text） */
  - 离线/错误：`background: rgba(224,62,62,0.14); color:#E03E3E;`

---

## 四、候选 C — Linear

### 为何贴合
Linear 是开发者工具美学的标杆：Inter 字体、信息密度高、深色优先、状态色体系（issue states）极其成熟。它把「技术受众 + 状态监控 + 危险确认」做成了行业标准，正好命中运维后台的核心交互。

### 适配性说明
- **集群健康 / 频道进程监控 / 日志告警** ↔ Linear 的状态标签与「确认→执行→结果提示」交互范式可直接复用。
- **深色模式** 是 Linear 的原生形态，浅色作为扩展位成本低。
- 密度高于 Apple/Notion，最契合「高频、长时间停留、信息密度中等偏紧」。
- 品牌主色 `#5E6AD2`（indigo）可作为控制台 accent，低调且有辨识度。

### 设计令牌草案（CSS 变量）

```css
/* ===== Linear · 浅色（默认；Linear 原生为深色，此处按需求以浅色为默认） ===== */
:root {
  /* —— 表面 / 背景 —— */
  --color-bg: #FFFFFF;
  --color-bg-secondary: #F7F8F8;        /* rgb(247,248,248) */
  --color-surface: #FFFFFF;
  --color-surface-hover: rgba(0,0,0,0.04);

  /* —— 文本 —— */
  --color-text-primary: #2C2C2C;
  --color-text-secondary: rgba(0,0,0,0.55);
  --color-text-tertiary: rgba(0,0,0,0.55);  /* 评审收紧：原 0.38≈2.8:1 → 现 0.55≈4.76:1（达正文 4.5:1；纯黑基底，提一档即达标） */

  /* —— 边框 —— */
  --color-border: #E6E6E6;              /* ≈ rgba(0,0,0,0.08) */

  /* —— 语义色（Linear 品牌/状态色，真实值） —— */
  --color-primary: #5E6AD2;             /* Linear 标志性 indigo */
  --color-primary-hover: #6E79E0;
  --color-success: #4CB782;
  --color-warning: #F2A03A;             /* 仅用于非文字（图标/底色/徽标填充） */
  --color-warning-text: #9A6700;        /* 评审新增：warning 作文字对白底≈4.9:1（原 #F2A03A≈2.4:1 不达标） */
  --color-danger: #EB5757;
  --color-info: #4EA7FC;

  /* —— 排版 —— */
  --font-sans: "Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
  --text-xs: 12px; --text-sm: 13px; --text-base: 14px; --text-md: 15px;
  --text-lg: 16px; --text-xl: 18px; --text-2xl: 22px; --text-3xl: 28px;
  --weight-regular: 400; --weight-medium: 500; --weight-semibold: 600; --weight-bold: 700;
  --leading-tight: 1.3; --leading-base: 1.45;
  /* 提示：Inter 建议开启 font-feature-settings: "cv11","ss01","liga" 1; 以匹配 Linear 字形 */

  /* —— 间距（4px 基准） —— */
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-5: 20px; --space-6: 24px; --space-8: 32px; --space-10: 40px; --space-12: 48px;

  /* —— 圆角 —— */
  --radius-sm: 4px; --radius-md: 6px; --radius-lg: 8px; --radius-pill: 999px;

  /* —— 阴影 —— */
  --shadow-sm: 0 1px 2px rgba(0,0,0,0.06);
  --shadow-md: 0 4px 16px rgba(0,0,0,0.10);
  --shadow-lg: 0 12px 32px rgba(0,0,0,0.18);
}

/* ===== Linear · 深色（原生主形态，预留扩展位） ===== */
[data-theme="dark"] {
  --color-bg: #0D0E10;                  /* Linear 应用主背景 */
  --color-bg-secondary: #16171A;
  --color-surface: #16171A;            /* 或 #1C1D20 */
  --color-surface-hover: rgba(255,255,255,0.06);
  --color-border: #23252A;
  --color-text-primary: #F7F8F8;
  --color-text-secondary: #8A8F98;
  --color-text-tertiary: #7A808A;        /* 评审收紧：原 #62666D≈3.4:1 → 现 #7A808A≈4.8:1（深底，达正文 4.5:1；仍暗于 secondary #8A8F98 保留层级） */
  --color-primary: #5E6AD2;
  --color-primary-hover: #6E79E0;
  --color-success: #4CB782;
  --color-warning: #F2A03A;
  --color-warning-text: #E8AE5A;        /* 深底下的 warning 文字（亮一档保对比度） */
  --color-danger: #EB5757;
  --color-info: #4EA7FC;
}
```

**关键组件规范（Linear）**
- **按钮·主**：`background: var(--color-primary); color:#fff; border-radius: var(--radius-md); padding: 6px 12px;` hover → `--color-primary-hover`。
- **按钮·次**：`background: transparent; border:1px solid var(--color-border); color: var(--color-text-primary);` hover → `--color-surface-hover`。
- **按钮·危险**：`background: var(--color-danger); color:#fff;`。
- **表格**：高密度，行高 32–36px，行间 1px `--color-border` 分隔，表头 `--text-secondary` 13px 500，行 hover `rgba(0,0,0,0.03)`（深色 `rgba(255,255,255,0.04)`）。
- **卡片**：`background: var(--color-surface); border:1px solid var(--color-border); border-radius: var(--radius-lg); padding: var(--space-4);`。
- **状态标签（在线/异常/离线）**：小胶囊，淡底实字（Linear issue-state 风格）——
  - 在线/正常：`background: rgba(76,183,130,0.16); color:#2E9E6B;`
  - 异常/警告：`background: rgba(242,160,58,0.16); color:#9A6700;`  /* 文字用 warning-text 层级 */
  - 离线/错误：`background: rgba(235,87,87,0.16); color:#EB5757;`

---

## 五、浅色 / 深色共用机制（三套通用）

三套候选**共用同一套变量名**，仅通过主题作用域切换取值，无需改组件代码：

```css
/* 默认（浅色）：写在 :root */
:root { /* 浅色令牌 */ }

/* 方案 1：显式切换（推荐用于后台，用户可控）
   在 <html> 或根容器加 data-theme="dark" */
[data-theme="dark"] { /* 深色令牌 */ }

/* 方案 2：跟随系统（可选，作为首次访问默认值）
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) { /* 深色令牌 */ }
} */
```

**建议落地策略**：
1. 以 `:root` 承载浅色令牌；`[data-theme="dark"]` 承载深色令牌。
2. 控制台提供「浅 / 深 / 跟随系统」三态开关，状态持久化到 `localStorage`，并写到 `<html data-theme>`。
3. 组件样式**只引用变量名**（如 `background: var(--color-surface)`），主题切换零改组件。
4. 语义色（success/warning/danger）在深皮下普遍需提亮一档以保证 WCAG AA 对比度（上表已处理）；**warning 作文字时必须用 `--color-warning-text` 而非 `--color-warning`**。

---

## 六、主推荐（供拍板，最终由用户决定）

**最建议以 Notion 作为主设计系统**：其内容优先、中性暖灰、侧边栏区块导航与表格友好的特质，最贴合本项目的「多区块总览仪表盘 + 左侧/顶部锚点导航 + 玩家管理表格 / 配置中心」，且浅深双主题原生、视觉噪声最低，让运维数据本身成为主角；同时可从 **Apple** 借用极致克制的留白与抛光细节，从 **Linear** 借用成熟的深色形态与「在线/异常/离线」状态色体系，作为深色模式与告警/状态标签的借鉴。以上三套均为 71 套库内真实系统，**最终选型权在用户（老板）**——请确认采用哪套（或指定混合方案），批准后我方再进入 Phase 3 原型生成。

---

## 七、评审修订明细（2025-08-06 · 严过审）

| # | 位置 | 原值 | 现值 | 浅底对比度变化 | 说明 |
|---|------|------|------|------|------|
| 1 | Notion `--color-text-tertiary`（浅） | `rgba(55,53,47,0.40)` ≈#AFAEAC | `rgba(55,53,47,0.60)` | 2.2:1 → 3.65:1 | 达大字 3:1；小号正文改用 secondary（0.65≈4.2:1） |
| 2 | Notion `--color-text-tertiary`（深） | `rgba(255,255,255,0.35)` | `rgba(255,255,255,0.50)` | 3.2:1 → 5.2:1 | 达正文 4.5:1 |
| 3 | Notion 新增 `--color-warning-text` | — | `#9A6700` | — → 4.9:1 | warning 作文字专色（评审示例 #B7791F≈3.6:1 仅达大字，故取更深值保小号 4.5:1） |
| 4 | Apple `--color-text-tertiary`（浅） | `rgba(60,60,67,0.30)` | `rgba(60,60,67,0.58)` | 2.0:1 → 3.3:1 | 预防性收紧（同原则） |
| 5 | Apple `--color-text-tertiary`（深） | `rgba(235,235,245,0.30)` | `rgba(235,235,245,0.50)` | — → 4.6:1 | 预防性收紧 |
| 6 | Apple 新增 `--color-warning-text` | — | `#9A6700`（浅）/ `#FFB340`（深） | — → 4.9:1 | 预防性，避免 warning 作文字复犯 |
| 7 | Linear `--color-text-tertiary`（浅） | `rgba(0,0,0,0.38)` | `rgba(0,0,0,0.55)` | 2.8:1 → 4.76:1 | 预防性收紧（纯黑基底，一档即达标） |
| 8 | Linear `--color-text-tertiary`（深） | `#62666D` | `#7A808A` | 3.4:1 → 4.8:1 | 预防性收紧，仍暗于 secondary 保留层级 |
| 9 | Linear 新增 `--color-warning-text` | — | `#9A6700`（浅）/ `#E8AE5A`（深） | — → 4.9:1 | 预防性 |

> 深色语义色（success/danger）经评审确认已提亮达标，未改动。其余令牌维持原评审结论 OK。
