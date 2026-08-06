import { motion, useReducedMotion, type Variants } from "framer-motion";
import { useOverview } from "./useOverview";
import { StatCard } from "@/components/ui/stat-card";
import { Card } from "@/components/ui/card";
import { StatusBadge } from "@/components/ui/status";
import { Badge } from "@/components/ui/badge";

// 纯 CSS 柱状图数据（在线玩家 24h 趋势，占位）
const TREND = [62, 70, 68, 75, 82, 88, 84, 90, 95, 92, 98, 100];

export function OverviewPage() {
  const { data, isLoading } = useOverview();
  const reduce = useReducedMotion();

  if (isLoading || !data) {
    return (
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div
            key={i}
            className="h-[116px] animate-pulse rounded-lg border border-border bg-card"
          />
        ))}
      </div>
    );
  }

  const container: Variants = reduce
    ? {}
    : { show: { transition: { staggerChildren: 0.06 } } };
  const item: Variants = reduce
    ? {}
    : { hidden: { opacity: 0, y: 12 }, show: { opacity: 1, y: 0 } };

  return (
    <motion.div
      variants={container}
      initial={reduce ? undefined : "hidden"}
      animate={reduce ? undefined : "show"}
      className="space-y-6"
    >
      <div>
        <h1 className="text-[20px] font-medium tracking-tight text-foreground">
          概览
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          实时服务状态与关键指标
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <motion.div variants={item}>
          <StatCard
            label="在线玩家"
            value={data.onlinePlayers.toLocaleString()}
            hint={<Badge variant="online">较昨日 +4.2%</Badge>}
          />
        </motion.div>
        <motion.div variants={item}>
          <StatCard
            label="活跃频道"
            value={data.activeChannels}
            hint={
              <span className="inline-flex items-center gap-1.5">
                <span className="size-2 rounded-full bg-online" />
                全部在线
              </span>
            }
          />
        </motion.div>
        <motion.div variants={item}>
          <StatCard
            label="系统负载"
            value={`${data.systemLoad}%`}
            hint={
              <span className="inline-flex items-center gap-1.5">
                <span
                  className={
                    data.systemLoad > 80
                      ? "size-2 rounded-full bg-warning"
                      : "size-2 rounded-full bg-online"
                  }
                />
                {data.systemLoad > 80 ? "偏高" : "健康"}
              </span>
            }
          />
        </motion.div>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <motion.div variants={item} className="lg:col-span-2">
          <Card className="p-5">
            <div className="flex items-center justify-between">
              <h2 className="text-[14px] font-medium text-foreground">
                频道状态
              </h2>
              <Badge variant="secondary">共 {data.channels.length} 个</Badge>
            </div>
            <div className="mt-2 overflow-x-auto">
              <table className="w-full text-[13px]">
                <thead>
                  <tr className="text-left text-[12px] text-muted-foreground">
                    <th className="pb-2 font-normal">频道</th>
                    <th className="pb-2 font-normal">状态</th>
                    <th className="pb-2 text-right font-normal">在线</th>
                  </tr>
                </thead>
                <tbody>
                  {data.channels.map((c) => (
                    <tr
                      key={c.id}
                      className="border-t border-border"
                    >
                      <td className="py-3 font-medium text-foreground">
                        {c.name}
                      </td>
                      <td className="py-3">
                        <StatusBadge
                          tone={c.status === "online" ? "online" : "offline"}
                        />
                      </td>
                      <td className="py-3 text-right tabular-nums text-foreground">
                        {c.online.toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </motion.div>

        <motion.div variants={item}>
          <Card className="flex h-full flex-col p-5">
            <h2 className="text-[14px] font-medium text-foreground">
              在线趋势
            </h2>
            <p className="mt-0.5 text-[12px] text-muted-foreground">
              近 12 个时段
            </p>
            <div className="mt-4 flex flex-1 items-end gap-1.5">
              {TREND.map((v, i) => (
                <div
                  key={i}
                  className="flex-1 rounded-sm bg-primary/80 transition-all hover:bg-primary"
                  style={{ height: `${v}%`, minHeight: "6px" }}
                  title={`时段 ${i + 1}：约 ${Math.round((v / 100) * data.onlinePlayers)} 人在线`}
                />
              ))}
            </div>
          </Card>
        </motion.div>
      </div>

      <motion.div variants={item}>
        <Card className="p-5">
          <h2 className="text-[14px] font-medium text-foreground">实时事件</h2>
          <ul className="mt-3 space-y-2.5 text-[13px]">
            {data.events.map((e) => (
              <li
                key={e.id}
                className="flex items-center gap-2 text-muted-foreground"
              >
                <span className="size-1.5 rounded-full bg-primary" />
                <span className="text-foreground">{e.label}</span>
                <span className="ml-auto text-[12px] text-faint-foreground">
                  {e.at}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      </motion.div>
    </motion.div>
  );
}
