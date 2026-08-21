import { Activity, CalendarClock, CircleAlert, Loader2, Play, RefreshCw, RotateCcw } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import {
  adminApi,
  adminQueryKeys,
  type BackgroundTaskRun,
  type TaskSchedule,
} from "@/api/admin"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useI18n } from "@/i18n"

type PendingAction =
  | { kind: "run"; schedule: TaskSchedule }
  | { kind: "retry"; task: BackgroundTaskRun }

export function TasksPage() {
  const { locale, t } = useI18n()
  const queryClient = useQueryClient()
  const [action, setAction] = useState<PendingAction | null>(null)
  const [enabledAction, setEnabledAction] = useState<{ schedule: TaskSchedule; enabled: boolean } | null>(null)
  const schedulesQuery = useQuery({
    queryKey: adminQueryKeys.schedules,
    queryFn: ({ signal }) => adminApi.schedules(signal),
    refetchInterval: 10_000,
  })
  const tasksQuery = useQuery({
    queryKey: adminQueryKeys.tasks,
    queryFn: ({ signal }) => adminApi.tasks(100, signal),
    refetchInterval: 10_000,
  })

  const refresh = () => {
    void schedulesQuery.refetch()
    void tasksQuery.refetch()
  }
  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: adminQueryKeys.schedules }),
      queryClient.invalidateQueries({ queryKey: adminQueryKeys.tasks }),
    ])
  }
  const actionMutation = useMutation({
    mutationFn: ({ action, reason }: { action: PendingAction; reason: string }) => action.kind === "run"
      ? adminApi.runSchedule(action.schedule.scheduleId, reason)
      : adminApi.retryTask(action.task.taskId, reason),
    onSuccess: async () => {
      setAction(null)
      await invalidate()
      toast.success(t("tasks.actionCompleted"))
    },
    onError: (error) => toast.error(t("tasks.actionFailed"), { description: error.message }),
  })
  const enabledMutation = useMutation({
    mutationFn: ({ scheduleId, enabled, reason }: { scheduleId: string; enabled: boolean; reason: string }) =>
      adminApi.setScheduleEnabled(scheduleId, enabled, reason),
    onSuccess: async () => {
      await invalidate()
      toast.success(t("tasks.scheduleUpdated"))
    },
    onError: (error) => toast.error(t("tasks.actionFailed"), { description: error.message }),
  })

  const schedules = schedulesQuery.data?.schedules ?? []
  const tasks = tasksQuery.data?.tasks ?? []
  const failedCount = tasksQuery.data?.metrics.failedRuns ?? tasks.filter((task) => task.status === "failed").length
  const runningCount = tasksQuery.data?.metrics.runningRuns ?? tasks.filter((task) => task.status === "running").length
  const error = schedulesQuery.error ?? tasksQuery.error
  const pending = schedulesQuery.isPending || tasksQuery.isPending

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("tasks.title")}
        description={t("tasks.description")}
        action={(
          <Button variant="outline" onClick={refresh} disabled={schedulesQuery.isFetching || tasksQuery.isFetching}>
            <RefreshCw data-icon="inline-start" className={(schedulesQuery.isFetching || tasksQuery.isFetching) ? "animate-spin" : ""} />
            {t("common.refresh")}
          </Button>
        )}
      />

      {error && <QueryError error={error as Error} retry={refresh} />}

      <section className="grid gap-4 sm:grid-cols-3" aria-label={t("tasks.metrics") }>
        <MetricCard title={t("tasks.registeredSchedules")} value={error ? "—" : (tasksQuery.data?.metrics.registeredSchedules ?? schedules.length)} icon={CalendarClock} pending={pending} />
        <MetricCard title={t("tasks.running")} value={error ? "—" : runningCount} icon={Activity} pending={pending} />
        <MetricCard title={t("tasks.failedSample")} value={error ? "—" : failedCount} icon={CircleAlert} pending={pending} />
      </section>

      <Card>
        <CardHeader>
          <CardTitle>{t("tasks.schedules")}</CardTitle>
          <CardDescription>{t("tasks.schedulesDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          {schedulesQuery.isPending ? <TableSkeleton /> : schedules.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted-foreground">
              {schedulesQuery.error ? t("tasks.unavailable") : t("tasks.noSchedules")}
            </p>
          ) : (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{t("tasks.name")}</TableHead>
                <TableHead>{t("tasks.schedule")}</TableHead>
                <TableHead>{t("tasks.lastRun")}</TableHead>
                <TableHead>{t("tasks.nextRun")}</TableHead>
                <TableHead>{t("tasks.status")}</TableHead>
                <TableHead className="text-right">{t("common.operation")}</TableHead>
              </TableRow></TableHeader>
              <TableBody>{schedules.map((schedule) => (
                <TableRow key={schedule.scheduleId}>
                  <TableCell>
                    <div className="font-medium">{schedule.displayName}</div>
                    <div className="font-mono text-xs text-muted-foreground">{schedule.scheduleId}</div>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{schedule.schedule}</TableCell>
                  <TableCell className="text-xs text-muted-foreground">{formatDate(schedule.lastRunAt, locale)}</TableCell>
                  <TableCell className="text-xs text-muted-foreground">{formatDate(schedule.nextRunAt, locale)}</TableCell>
                  <TableCell><div className="flex flex-wrap items-center gap-2">
                    <Badge variant={schedule.enabled ? "secondary" : "outline"}>{schedule.enabled ? t("tasks.enabled") : t("tasks.disabled")}</Badge>
                    {schedule.lastStatus && <TaskStatus status={schedule.lastStatus} />}
                  </div></TableCell>
                  <TableCell className="text-right"><div className="flex justify-end gap-2">
                    <Button size="sm" variant="outline" onClick={() => setEnabledAction({ schedule, enabled: !schedule.enabled })}>
                      {schedule.enabled ? t("tasks.disable") : t("tasks.enable")}
                    </Button>
                    <Button size="sm" onClick={() => setAction({ kind: "run", schedule })}>
                      <Play data-icon="inline-start" />{t("tasks.runNow")}
                    </Button>
                  </div></TableCell>
                </TableRow>
              ))}</TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("tasks.history")}</CardTitle>
          <CardDescription>{t("tasks.historyDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          {tasksQuery.isPending ? <TableSkeleton /> : tasks.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted-foreground">
              {tasksQuery.error ? t("tasks.unavailable") : t("tasks.noHistory")}
            </p>
          ) : (
            <Table>
              <TableHeader><TableRow>
                <TableHead>{t("tasks.name")}</TableHead>
                <TableHead>{t("tasks.status")}</TableHead>
                <TableHead>{t("tasks.trigger")}</TableHead>
                <TableHead>{t("tasks.startedAt")}</TableHead>
                <TableHead>{t("tasks.duration")}</TableHead>
                <TableHead>{t("tasks.result")}</TableHead>
                <TableHead className="text-right">{t("common.operation")}</TableHead>
              </TableRow></TableHeader>
              <TableBody>{tasks.map((task) => (
                <TableRow key={task.taskId}>
                  <TableCell>
                    <div className="font-medium">{task.displayName}</div>
                    <div className="font-mono text-xs text-muted-foreground">{task.taskId}</div>
                  </TableCell>
                  <TableCell><TaskStatus status={task.status} /></TableCell>
                  <TableCell className="text-xs">{task.trigger}</TableCell>
                  <TableCell className="text-xs text-muted-foreground">{formatDate(task.startedAt, locale)}</TableCell>
                  <TableCell className="text-xs">{formatDuration(task.durationMs)}</TableCell>
                  <TableCell className="max-w-xs text-xs text-muted-foreground">{task.errorSummary ?? t("tasks.completed")}</TableCell>
                  <TableCell className="text-right">
                    {task.status === "failed" && task.retryable && (
                      <Button size="sm" variant="outline" onClick={() => setAction({ kind: "retry", task })}>
                        <RotateCcw data-icon="inline-start" />{t("tasks.retry")}
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}</TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <ConfirmationDialog
        open={action !== null}
        onOpenChange={(open) => !open && setAction(null)}
        title={action?.kind === "retry" ? t("tasks.confirmRetry") : t("tasks.confirmRun")}
        description={action?.kind === "retry" ? t("tasks.confirmRetryDescription") : t("tasks.confirmRunDescription")}
        confirmLabel={action?.kind === "retry" ? t("tasks.retry") : t("tasks.runNow")}
        pending={actionMutation.isPending}
        requireReason
        onConfirm={(reason) => action && actionMutation.mutate({ action, reason })}
      />

      <ConfirmationDialog
        open={enabledAction !== null}
        onOpenChange={(open) => !open && setEnabledAction(null)}
        title={enabledAction?.enabled ? t("tasks.enable") : t("tasks.disable")}
        description={t("tasks.schedulesDescription")}
        confirmLabel={enabledAction?.enabled ? t("tasks.enable") : t("tasks.disable")}
        pending={enabledMutation.isPending}
        requireReason
        onConfirm={(reason) => enabledAction && enabledMutation.mutate({
          scheduleId: enabledAction.schedule.scheduleId,
          enabled: enabledAction.enabled,
          reason,
        })}
      />
    </div>
  )
}

function MetricCard({ title, value, icon: Icon, pending }: { title: string; value: string | number; icon: typeof Activity; pending: boolean }) {
  return <Card><CardContent className="flex items-center justify-between py-5">
    <div><p className="text-sm text-muted-foreground">{title}</p>{pending ? <Skeleton className="mt-2 h-7 w-14" /> : <p className="mt-1 text-2xl font-semibold">{value}</p>}</div>
    <Icon className="size-5 text-muted-foreground" />
  </CardContent></Card>
}

function TableSkeleton() {
  return <div className="grid gap-3 py-2">{[0, 1, 2].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
}

function TaskStatus({ status }: { status: BackgroundTaskRun["status"] }) {
  const { t } = useI18n()
  if (status === "failed") return <Badge variant="destructive">{t("tasks.statusFailed")}</Badge>
  if (status === "running") return <Badge><Loader2 className="animate-spin" />{t("tasks.statusRunning")}</Badge>
  if (status === "cancelled") return <Badge variant="outline">{t("tasks.statusCancelled")}</Badge>
  return <Badge variant="secondary">{t("tasks.statusSucceeded")}</Badge>
}

function formatDate(value: string | null, locale: string) {
  if (!value) return "—"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(date)
}

function formatDuration(value: number | null) {
  if (value == null) return "—"
  return value < 1_000 ? `${value} ms` : `${(value / 1_000).toFixed(1)} s`
}
