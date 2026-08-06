import { NavLink } from "react-router-dom";
import {
  LayoutDashboard,
  Radio,
  Users,
  SlidersHorizontal,
  Puzzle,
  Sparkles,
  ServerCog,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";

interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  end?: boolean;
}

const NAV: NavItem[] = [
  { to: "/", label: "概览", icon: LayoutDashboard, end: true },
  { to: "/channels", label: "频道管理", icon: Radio },
  { to: "/players", label: "玩家管理", icon: Users },
  { to: "/config", label: "配置中心", icon: SlidersHorizontal },
  { to: "/plugins", label: "插件", icon: Puzzle },
  { to: "/ai", label: "AI 助手", icon: Sparkles },
  { to: "/ops", label: "运维 & 迁移", icon: ServerCog },
];

export function Sidebar() {
  return (
    <aside className="flex w-[224px] shrink-0 flex-col gap-1 border-r border-border bg-surface-sunken px-3 py-5">
      <div className="mb-5 flex items-center gap-2 px-2">
        <span className="flex size-7 items-center justify-center rounded-md bg-primary text-[13px] font-semibold text-primary-foreground">
          T
        </span>
        <span className="text-[15px] font-medium text-foreground">
          Twinkle
        </span>
      </div>

      <nav className="flex flex-col gap-0.5" aria-label="主导航">
        {NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              cn(
                "flex items-center gap-2.5 rounded-md px-3 py-2 text-[13px] font-medium transition-colors",
                isActive
                  ? "bg-primary-soft text-primary"
                  : "text-muted-foreground hover:bg-card hover:text-foreground",
              )
            }
          >
            <item.icon className="size-4" />
            {item.label}
          </NavLink>
        ))}
      </nav>

      <div className="mt-auto px-2 pt-4 text-[11px] text-faint-foreground">
        v0.1.0 · 运维控制台
      </div>
    </aside>
  );
}
