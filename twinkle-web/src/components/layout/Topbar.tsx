import { Search, Bell } from "lucide-react";
import { useSession } from "../../lib/store/session";
import { Input } from "@/components/ui/input";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { StatusDot } from "@/components/ui/status";
import { ThemeToggle } from "@/components/theme-toggle";

export function Topbar() {
  const user = useSession((s) => s.user);
  return (
    <header className="flex h-14 shrink-0 items-center gap-4 border-b border-border bg-background px-6">
      <div className="flex flex-1 items-center">
        <div className="relative w-full max-w-md">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="pl-9"
            placeholder="搜索玩家 / 频道 / 配置"
            aria-label="全局搜索"
          />
        </div>
      </div>

      <span className="hidden items-center gap-2 text-[12px] text-muted-foreground sm:flex">
        <StatusDot tone="online" pulse />
        运行中
      </span>

      <Button
        variant="ghost"
        size="icon"
        aria-label="通知"
        className="relative"
      >
        <Bell className="size-4" />
        <span className="absolute right-2 top-2 size-1.5 rounded-full bg-destructive" />
      </Button>

      <ThemeToggle />

      <Avatar>
        <AvatarFallback>
          {user?.[0]?.toUpperCase() ?? "A"}
        </AvatarFallback>
      </Avatar>
    </header>
  );
}
