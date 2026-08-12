import { Activity, Radio, RefreshCw, Users } from "lucide-react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { adminApi, adminQueryKeys } from "@/api/admin"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { useI18n } from "@/i18n"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

export function OverviewPage() {
  const { t, formatNumber } = useI18n()
  const queryClient = useQueryClient()
  const health = useQuery({
    queryKey: adminQueryKeys.health,
    queryFn: ({ signal }) => adminApi.health(signal),
    refetchInterval: 10_000,
  })
  const channels = useQuery({
    queryKey: adminQueryKeys.channels,
    queryFn: ({ signal }) => adminApi.channels(signal),
    refetchInterval: 10_000,
  })
  const online = useQuery({
    queryKey: adminQueryKeys.online,
    queryFn: ({ signal }) => adminApi.online(signal),
    refetchInterval: 5_000,
  })

  const firstError = health.error ?? channels.error ?? online.error
  const isRefreshing = health.isFetching || channels.isFetching || online.isFetching

  function refreshAll() {
    void queryClient.invalidateQueries({ queryKey: ["admin"] })
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("overview.title")}
        description={t("overview.description")}
        action={
          <Button variant="outline" size="sm" onClick={refreshAll} disabled={isRefreshing}>
            <RefreshCw data-icon="inline-start" className={isRefreshing ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      {firstError && <QueryError error={firstError} retry={refreshAll} />}

      <section className="grid gap-3 sm:grid-cols-3" aria-label={t("overview.metrics")}>
        <MetricCard
          icon={Activity}
          label={t("overview.health")}
          loading={health.isPending}
          value={health.data ? (health.data.healthy ? t("overview.healthy") : t("overview.unhealthy")) : "—"}
          detail={
            health.data
              ? t("overview.healthChecks", { count: Object.keys(health.data.checks).length })
              : t("overview.waiting")
          }
          status={health.data?.healthy}
        />
        <MetricCard
          icon={Radio}
          label={t("overview.channelCount")}
          loading={channels.isPending}
          value={channels.data ? formatNumber(channels.data.channels.length) : "—"}
          detail={t("overview.refresh10")}
        />
        <MetricCard
          icon={Users}
          label={t("overview.onlinePlayers")}
          loading={online.isPending}
          value={online.data ? formatNumber(online.data.onlineCount) : "—"}
          detail={t("overview.refresh5")}
        />
      </section>

      <Card>
        <CardHeader>
          <CardTitle>{t("overview.channelStatus")}</CardTitle>
          <CardDescription>{t("overview.channelStatusDescription")}</CardDescription>
          <CardAction>
            <Button variant="ghost" size="sm" asChild>
              <Link to="/channels">{t("overview.viewAll")}</Link>
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent>
          {channels.isPending ? (
            <TableSkeleton />
          ) : channels.error && !channels.data ? (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("overview.channelUnavailable")}</p>
          ) : channels.data && channels.data.channels.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("table.channel")}</TableHead>
                  <TableHead>{t("table.address")}</TableHead>
                  <TableHead className="text-right">{t("table.onlinePlayers")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {channels.data.channels.slice(0, 5).map((channel) => (
                  <TableRow key={channel.channelId}>
                    <TableCell className="font-medium">{t("table.channel")} {channel.channelId}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">
                      {channel.host}:{channel.port}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{channel.onlineCount}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("overview.noChannels")}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function MetricCard({
  icon: Icon,
  label,
  value,
  detail,
  loading,
  status,
}: {
  icon: typeof Activity
  label: string
  value: string
  detail: string
  loading: boolean
  status?: boolean
}) {
  const { t } = useI18n()
  return (
    <Card className="shadow-none">
      <CardHeader className="gap-3">
        <div className="flex items-center justify-between">
          <CardDescription>{label}</CardDescription>
          <Icon className="size-4 text-muted-foreground" />
        </div>
        {loading ? (
          <Skeleton className="h-7 w-20" />
        ) : (
          <div className="flex items-center gap-2">
            <CardTitle>{value}</CardTitle>
            {status !== undefined && (
              <Badge variant={status ? "secondary" : "destructive"}>
                {status ? t("overview.healthy") : t("overview.unhealthy")}
              </Badge>
            )}
          </div>
        )}
      </CardHeader>
      <CardContent className="text-xs text-muted-foreground">{detail}</CardContent>
    </Card>
  )
}

function TableSkeleton() {
  return (
    <div className="grid gap-3 py-2">
      {[0, 1, 2].map((row) => (
        <Skeleton key={row} className="h-9 w-full" />
      ))}
    </div>
  )
}
