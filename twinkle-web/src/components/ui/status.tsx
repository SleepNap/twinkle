import { cn } from "@/lib/utils";

type Tone = "online" | "offline" | "warning" | "destructive";

const TONE: Record<Tone, { dot: string; label: string }> = {
  online: { dot: "bg-online", label: "在线" },
  offline: { dot: "bg-faint-foreground", label: "离线" },
  warning: { dot: "bg-warning", label: "异常" },
  destructive: { dot: "bg-destructive", label: "错误" },
};

interface StatusDotProps {
  tone?: Tone;
  pulse?: boolean;
  className?: string;
  label?: string;
}

/** 单点状态指示：用于表格行、行内状态。默认带可访问名称。 */
export function StatusDot({
  tone = "online",
  pulse = false,
  className,
  label,
}: StatusDotProps) {
  const t = TONE[tone];
  return (
    <span
      className={cn(
        "inline-block h-2.5 w-2.5 rounded-full",
        t.dot,
        pulse && "animate-pulse",
        className,
      )}
      role="img"
      aria-label={label ?? t.label}
    />
  );
}

interface StatusBadgeProps {
  tone?: Tone;
  label?: string;
  className?: string;
}

/** 胶囊状态标签：浅底实字（Notion/Linear tag 风格）。 */
export function StatusBadge({ tone = "online", label, className }: StatusBadgeProps) {
  const map: Record<Tone, string> = {
    online: "bg-online-soft text-online",
    offline: "bg-secondary text-muted-foreground",
    warning: "bg-warning-soft text-warning",
    destructive: "bg-destructive-soft text-destructive",
  };
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[12px] font-medium",
        map[tone],
        className,
      )}
    >
      <span className={cn("h-1.5 w-1.5 rounded-full", TONE[tone].dot)} />
      {label ?? TONE[tone].label}
    </span>
  );
}
