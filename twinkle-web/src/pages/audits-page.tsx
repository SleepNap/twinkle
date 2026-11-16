import { Bot, Eye, RefreshCw, ShieldCheck, ShieldX } from "lucide-react"
import { useQuery } from "@tanstack/react-query"
import { useState } from "react"

import { adminApi, adminQueryKeys, type ApiRequestAudit, type ToolExecutionAudit } from "@/api/admin"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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

const EMPTY_VALUE = "-"

export function AuditsPage() {
  const { locale, t } = useI18n()
  const apiRequests = useQuery({
    queryKey: adminQueryKeys.apiRequestAudits,
    queryFn: ({ signal }) => adminApi.apiRequestAudits(100, signal),
    refetchInterval: 15_000,
  })
  const toolExecutions = useQuery({
    queryKey: adminQueryKeys.toolExecutionAudits,
    queryFn: ({ signal }) => adminApi.toolExecutionAudits(200, signal),
    refetchInterval: 15_000,
  })
  const deniedCount = apiRequests.data
    ? apiRequests.data.records.filter((record) => record.outcome !== "allowed").length
    : undefined
  const failedTools = toolExecutions.data
    ? toolExecutions.data.records.filter((record) => record.resultStatus !== "succeeded").length
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
              <CardDescription>
                {t("audits.toolsRange", { count: toolExecutions.data?.limit ?? 200 })} {t("audits.safeSummary")}
              </CardDescription>
            </CardHeader>
            <CardContent>
              <p className="mb-3 text-xs text-muted-foreground">{t("audits.noToolOutput")}</p>
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
  const [selected, setSelected] = useState<ToolExecutionAudit | null>(null)
  if (records.length === 0) return <EmptyAudit />
  return (
    <>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>{t("audits.time")}</TableHead>
            <TableHead>{t("audits.tool")}</TableHead>
            <TableHead>{t("audits.subject")}</TableHead>
            <TableHead>{t("audits.authorization")}</TableHead>
            <TableHead>{t("audits.result")}</TableHead>
            <TableHead>{t("audits.task")}</TableHead>
            <TableHead className="text-right">{t("audits.detail")}</TableHead>
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
              <TableCell><Badge variant={record.authorizationResult === "allow" ? "secondary" : "destructive"}>{record.authorizationResult}</Badge></TableCell>
              <TableCell>
                <Badge variant={record.resultStatus === "succeeded" ? "secondary" : "destructive"}>{record.resultStatus}</Badge>
                {record.errorCode && <div className="mt-1 font-mono text-xs text-destructive">{record.errorCode}</div>}
              </TableCell>
              <TableCell>
                <div className="font-mono text-xs">{record.taskId || "—"}</div>
                <div className="font-mono text-[0.7rem] text-muted-foreground">{record.executionId}</div>
              </TableCell>
              <TableCell className="text-right">
                <Button variant="outline" size="xs" onClick={() => setSelected(record)}>
                  <Eye data-icon="inline-start" />
                  {t("audits.detail")}
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <ToolExecutionDetailDialog record={selected} locale={locale} onClose={() => setSelected(null)} />
    </>
  )
}

interface ToolExecutionDetailField {
  label: string
  value: string | null
  badge?: "authorization" | "result"
}

function ToolExecutionDetailDialog({ record, locale, onClose }: { record: ToolExecutionAudit | null; locale: string; onClose: () => void }) {
  const { t } = useI18n()
  const locateFields: ToolExecutionDetailField[] = record
    ? [
      { label: t("audits.field.executionId"), value: record.executionId },
      { label: t("audits.field.auditRef"), value: record.auditRef },
      { label: t("audits.field.requestId"), value: record.requestId },
      { label: t("audits.field.taskId"), value: record.taskId },
      { label: t("audits.field.stepId"), value: record.stepId },
      { label: t("audits.field.subjectId"), value: record.subjectId },
      { label: t("audits.field.credentialId"), value: record.credentialId },
      { label: t("audits.field.source"), value: record.source },
      { label: t("audits.field.serverId"), value: record.serverId },
      { label: t("audits.field.startedAt"), value: record.startedAt ? formatDate(record.startedAt, locale) : null },
      { label: t("audits.field.completedAt"), value: record.completedAt ? formatDate(record.completedAt, locale) : null },
    ]
    : []
  const resultFields: ToolExecutionDetailField[] = record
    ? [
      { label: t("audits.field.toolId"), value: record.toolId },
      { label: t("audits.field.toolVersion"), value: record.toolVersion },
      { label: t("audits.field.requiredScopes"), value: record.requiredScopes },
      { label: t("audits.field.parameterSummary"), value: record.parameterSummary },
      { label: t("audits.field.intentSummary"), value: record.intentSummary },
      { label: t("audits.field.authorizationResult"), value: record.authorizationResult, badge: "authorization" },
      { label: t("audits.field.policyVersion"), value: record.policyVersion },
      { label: t("audits.field.resultStatus"), value: record.resultStatus, badge: "result" },
      { label: t("audits.field.errorCode"), value: record.errorCode },
    ]
    : []

  return (
    <Dialog open={record !== null} onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-h-[88svh] overflow-y-auto sm:max-w-2xl">
        {record && (
          <>
            <DialogHeader>
              <DialogTitle>{t("audits.detailTitle")}</DialogTitle>
              <DialogDescription>{t("audits.detailDescription", { id: record.executionId })}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-1 rounded-lg border bg-muted/40 p-3 text-xs leading-relaxed text-muted-foreground">
              <p>{t("audits.detailNoticeOutput")}</p>
              <p>{t("audits.detailScope")}</p>
            </div>
            <ToolExecutionDetailGroup title={t("audits.detailLocate")} fields={locateFields} />
            <ToolExecutionDetailGroup title={t("audits.detailParams")} fields={resultFields} />
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

function ToolExecutionDetailGroup({ title, fields }: { title: string; fields: ToolExecutionDetailField[] }) {
  return (
    <section className="grid gap-2">
      <h3 className="text-sm font-medium">{title}</h3>
      <dl className="grid gap-x-4 gap-y-3 rounded-lg border p-3 sm:grid-cols-2">
        {fields.map((field) => (
          <div key={field.label} className="grid gap-1">
            <dt className="text-xs text-muted-foreground">{field.label}</dt>
            <dd className="min-w-0 text-xs break-all">
              <ToolExecutionDetailValue field={field} />
            </dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function ToolExecutionDetailValue({ field }: { field: ToolExecutionDetailField }) {
  const value = field.value && field.value.trim().length > 0 ? field.value : null
  if (!value) return <span className="text-muted-foreground">{EMPTY_VALUE}</span>
  if (field.badge === "authorization") return <Badge variant={value === "allow" ? "secondary" : "destructive"}>{value}</Badge>
  if (field.badge === "result") return <Badge variant={value === "succeeded" ? "secondary" : "destructive"}>{value}</Badge>
  return <span className="font-mono">{value}</span>
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
