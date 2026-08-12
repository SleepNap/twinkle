import { Bot, RefreshCw, ShieldCheck, ShieldX } from "lucide-react"
import { useQuery } from "@tanstack/react-query"

import { adminApi, adminQueryKeys, type ApiRequestAudit, type ToolExecutionAudit } from "@/api/admin"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useI18n } from "@/i18n"

export function AuditsPage() {
  const { locale, t } = useI18n()
  const apiRequests = useQuery({
    queryKey: adminQueryKeys.apiRequestAudits,
    queryFn: ({ signal }) => adminApi.apiRequestAudits(100, signal),
    refetchInterval: 15_000,
  })
  const toolExecutions = useQuery({
    queryKey: adminQueryKeys.toolExecutionAudits,
    queryFn: ({ signal }) => adminApi.toolExecutionAudits(100, signal),
    refetchInterval: 15_000,
  })
  const deniedCount = apiRequests.data
    ? apiRequests.data.records.filter((record) => record.outcome !== "allowed").length
    : undefined
  const failedTools = toolExecutions.data
    ? toolExecutions.data.records.filter((record) => record.resultStatus !== "success").length
    : undefined
  const firstError = apiRequests.error ?? toolExecutions.error
  const refreshing = apiRequests.isFetching || toolExecutions.isFetching

  function refreshAll() {
    void apiRequests.refetch()
    void toolExecutions.refetch()
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("audits.title")}
        description={t("audits.description")}
        action={
          <Button variant="outline" size="sm" onClick={refreshAll} disabled={refreshing}>
            <RefreshCw data-icon="inline-start" className={refreshing ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      {firstError && <QueryError error={firstError} retry={refreshAll} />}

      <section className="grid gap-3 sm:grid-cols-3" aria-label={t("audits.metrics")}>
        <AuditMetric icon={ShieldCheck} label={t("audits.apiTotal")} value={apiRequests.data?.total} loading={apiRequests.isPending} />
        <AuditMetric icon={ShieldX} label={t("audits.deniedSample")} value={deniedCount} loading={apiRequests.isPending} destructive={Boolean(deniedCount && deniedCount > 0)} />
        <AuditMetric icon={Bot} label={t("audits.toolTotal")} value={toolExecutions.data?.total} loading={toolExecutions.isPending} detail={failedTools === undefined ? "—" : t("audits.failedSample", { count: failedTools })} />
      </section>

      <Tabs defaultValue="api">
        <TabsList>
          <TabsTrigger value="api">{t("audits.apiTab")}</TabsTrigger>
          <TabsTrigger value="tools">{t("audits.toolsTab")}</TabsTrigger>
        </TabsList>
        <TabsContent value="api">
          <Card>
            <CardHeader>
              <CardTitle>{t("audits.apiTitle")}</CardTitle>
              <CardDescription>{t("audits.latest", { count: apiRequests.data?.limit ?? 100 })}</CardDescription>
            </CardHeader>
            <CardContent>
              {apiRequests.isPending ? <AuditSkeleton /> : apiRequests.error && !apiRequests.data ? (
                <p className="py-12 text-center text-sm text-muted-foreground">{t("audits.unavailable")}</p>
              ) : (
                <ApiRequestTable records={apiRequests.data?.records ?? []} locale={locale} />
              )}
            </CardContent>
          </Card>
        </TabsContent>
        <TabsContent value="tools">
          <Card>
            <CardHeader>
              <CardTitle>{t("audits.toolsTitle")}</CardTitle>
              <CardDescription>{t("audits.safeSummary")}</CardDescription>
            </CardHeader>
            <CardContent>
              {toolExecutions.isPending ? <AuditSkeleton /> : toolExecutions.error && !toolExecutions.data ? (
                <p className="py-12 text-center text-sm text-muted-foreground">{t("audits.unavailable")}</p>
              ) : (
                <ToolExecutionTable records={toolExecutions.data?.records ?? []} locale={locale} />
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}

function ApiRequestTable({ records, locale }: { records: ApiRequestAudit[]; locale: string }) {
  const { t } = useI18n()
  if (records.length === 0) return <EmptyAudit />
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{t("audits.time")}</TableHead>
          <TableHead>{t("audits.request")}</TableHead>
          <TableHead>{t("audits.scope")}</TableHead>
          <TableHead>{t("audits.outcome")}</TableHead>
          <TableHead>{t("audits.credential")}</TableHead>
          <TableHead className="text-right">{t("audits.duration")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {records.map((record) => (
          <TableRow key={record.id}>
            <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{formatDate(record.createdAt, locale)}</TableCell>
            <TableCell>
              <div className="flex items-center gap-2"><Badge variant="outline">{record.method}</Badge><span className="font-mono text-xs">{record.path}</span></div>
              <div className="mt-1 font-mono text-[0.7rem] text-muted-foreground">{record.requestId}</div>
            </TableCell>
            <TableCell className="font-mono text-xs">{record.requiredScope || "—"}</TableCell>
            <TableCell>
              <Badge variant={record.outcome === "allowed" ? "secondary" : "destructive"}>
                {record.outcome} · {record.statusCode}
              </Badge>
            </TableCell>
            <TableCell>
              <div className="font-mono text-xs">{record.keyPrefix || "—"}</div>
              <div className="text-xs text-muted-foreground">{record.remoteAddress}</div>
            </TableCell>
            <TableCell className="text-right tabular-nums">{record.elapsedMs} ms</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function ToolExecutionTable({ records, locale }: { records: ToolExecutionAudit[]; locale: string }) {
  const { t } = useI18n()
  if (records.length === 0) return <EmptyAudit />
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{t("audits.time")}</TableHead>
          <TableHead>{t("audits.tool")}</TableHead>
          <TableHead>{t("audits.subject")}</TableHead>
          <TableHead>{t("audits.authorization")}</TableHead>
          <TableHead>{t("audits.result")}</TableHead>
          <TableHead>{t("audits.task")}</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {records.map((record) => (
          <TableRow key={record.auditRef}>
            <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{formatDate(record.startedAt, locale)}</TableCell>
            <TableCell>
              <div className="font-mono text-xs font-medium">{record.toolId}</div>
              <div className="text-xs text-muted-foreground">v{record.toolVersion} · {record.source}</div>
            </TableCell>
            <TableCell>
              <div className="text-xs">{record.subjectId}</div>
              <div className="font-mono text-[0.7rem] text-muted-foreground">{record.credentialId}</div>
            </TableCell>
            <TableCell><Badge variant={record.authorizationResult === "allowed" ? "secondary" : "destructive"}>{record.authorizationResult}</Badge></TableCell>
            <TableCell>
              <Badge variant={record.resultStatus === "success" ? "secondary" : "destructive"}>{record.resultStatus}</Badge>
              {record.errorCode && <div className="mt-1 font-mono text-xs text-destructive">{record.errorCode}</div>}
            </TableCell>
            <TableCell>
              <div className="font-mono text-xs">{record.taskId || "—"}</div>
              <div className="font-mono text-[0.7rem] text-muted-foreground">{record.executionId}</div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function AuditMetric({ icon: Icon, label, value, detail, loading, destructive }: { icon: typeof ShieldCheck; label: string; value?: number; detail?: string; loading: boolean; destructive?: boolean }) {
  return (
    <Card className="shadow-none">
      <CardHeader>
        <div className="flex items-center justify-between"><CardDescription>{label}</CardDescription><Icon className={destructive ? "size-4 text-destructive" : "size-4 text-muted-foreground"} /></div>
        {loading ? <Skeleton className="h-7 w-16" /> : <CardTitle>{value ?? "—"}</CardTitle>}
      </CardHeader>
      {detail && <CardContent className="text-xs text-muted-foreground">{detail}</CardContent>}
    </Card>
  )
}

function EmptyAudit() {
  const { t } = useI18n()
  return <p className="py-12 text-center text-sm text-muted-foreground">{t("audits.empty")}</p>
}

function AuditSkeleton() {
  return <div className="grid gap-3 py-2">{[0, 1, 2, 3, 4].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
}

function formatDate(value: string, locale: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "medium" }).format(date)
}
