import type { ReactNode } from "react";
import { Card } from "@/components/ui/card";
import { cn } from "@/lib/utils";

interface StatCardProps {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  className?: string;
}

/** 概览指标卡：标签 + 大数值 + 辅助提示。克制、无装饰阴影。 */
export function StatCard({ label, value, hint, className }: StatCardProps) {
  return (
    <Card className={cn("p-5", className)}>
      <div className="text-[12px] font-medium text-muted-foreground">
        {label}
      </div>
      <div className="mt-2 text-[28px] font-medium leading-none tracking-tight text-foreground">
        {value}
      </div>
      {hint ? (
        <div className="mt-2 flex items-center gap-1.5 text-[12px] text-muted-foreground">
          {hint}
        </div>
      ) : null}
    </Card>
  );
}
