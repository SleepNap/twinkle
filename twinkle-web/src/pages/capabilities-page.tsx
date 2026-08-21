import { BookOpen, Download, Eye, RefreshCw, Search } from "lucide-react"
import { useQuery } from "@tanstack/react-query"
import { useState } from "react"
import { Link } from "react-router-dom"

import { capabilityApi, capabilityQueryKeys } from "@/api/capability"
import { useCredential } from "@/auth/use-credential"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
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

export function CapabilitiesPage() {
  const { token } = useCredential()
  const { t, formatNumber } = useI18n()
  const [search, setSearch] = useState("")
  const [submittedSearch, setSubmittedSearch] = useState("")
  const [selectedToolId, setSelectedToolId] = useState<string | null>(null)

  const catalogQuery = useQuery({
    queryKey: capabilityQueryKeys.catalog(submittedSearch),
    queryFn: ({ signal }) => capabilityApi.capabilities(token, submittedSearch, signal),
    enabled: Boolean(token),
    retry: false,
  })
  const detailQuery = useQuery({
    queryKey: capabilityQueryKeys.tool(selectedToolId ?? ""),
    queryFn: ({ signal }) => capabilityApi.capability(token, selectedToolId!, signal),
    enabled: Boolean(token && selectedToolId),
    retry: false,
  })

  function submitSearch() {
    setSubmittedSearch(search.trim())
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("capabilities.title")}
        description={t("capabilities.description")}
        action={(
          <div className="flex gap-2">
            <Button variant="outline" size="sm" asChild>
              <a href="/api/v1/openapi.yaml" download>
                <Download data-icon="inline-start" />
                {t("capabilities.downloadOpenApi")}
              </a>
            </Button>
            {token && (
              <Button variant="outline" size="sm" onClick={() => void catalogQuery.refetch()} disabled={catalogQuery.isFetching}>
                <RefreshCw data-icon="inline-start" className={catalogQuery.isFetching ? "animate-spin" : undefined} />
                {t("common.refresh")}
              </Button>
            )}
          </div>
        )}
      />

      {!token ? (
        <Card className="max-w-2xl">
          <CardHeader>
            <CardTitle>{t("capabilities.connectTitle")}</CardTitle>
            <CardDescription>{t("capabilities.connectDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild><Link to="/api-keys">{t("capabilities.connect")}</Link></Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <Alert>
            <BookOpen />
            <AlertTitle>{t("capabilities.visibilityTitle")}</AlertTitle>
            <AlertDescription>{t("capabilities.visibilityDescription")}</AlertDescription>
          </Alert>

          <Card>
            <CardHeader>
              <CardTitle>{t("capabilities.searchTitle")}</CardTitle>
              <CardDescription>{t("capabilities.searchDescription")}</CardDescription>
            </CardHeader>
            <CardContent className="flex gap-2">
              <div className="relative max-w-xl flex-1">
                <Search className="absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  className="pl-8"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  onKeyDown={(event) => event.key === "Enter" && submitSearch()}
                  placeholder={t("capabilities.searchPlaceholder")}
                />
              </div>
              <Button onClick={submitSearch}>{t("common.search")}</Button>
            </CardContent>
          </Card>

          {catalogQuery.error && <QueryError error={catalogQuery.error} retry={() => void catalogQuery.refetch()} />}

          <Card>
            <CardHeader>
              <CardTitle>{t("capabilities.catalog")}</CardTitle>
              <CardDescription>
                {t("capabilities.catalogDescription", {
                  count: formatNumber(catalogQuery.data?.tools.length ?? 0),
                  version: catalogQuery.data?.catalogVersion ?? "—",
                })}
              </CardDescription>
            </CardHeader>
            <CardContent className="overflow-x-auto">
              {catalogQuery.isPending ? (
                <div className="grid gap-3">{[0, 1, 2, 3].map((row) => <Skeleton key={row} className="h-12 w-full" />)}</div>
              ) : catalogQuery.data && catalogQuery.data.tools.length > 0 ? (
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>{t("capabilities.tool")}</TableHead><TableHead>{t("capabilities.categories")}</TableHead>
                    <TableHead>{t("capabilities.risk")}</TableHead><TableHead>{t("capabilities.availability")}</TableHead>
                    <TableHead className="w-24 text-right">{t("common.operation")}</TableHead>
                  </TableRow></TableHeader>
                  <TableBody>{catalogQuery.data.tools.map((tool) => (
                    <TableRow key={tool.toolId} data-state={selectedToolId === tool.toolId ? "selected" : undefined}>
                      <TableCell>
                        <div className="font-medium">{tool.title}</div>
                        <div className="font-mono text-xs text-muted-foreground">{tool.toolId} · v{tool.toolVersion}</div>
                        <div className="mt-1 max-w-xl text-xs text-muted-foreground">{tool.summary}</div>
                      </TableCell>
                      <TableCell><div className="flex flex-wrap gap-1">{tool.categories.map((category) => <Badge key={category} variant="outline">{category}</Badge>)}</div></TableCell>
                      <TableCell><Badge variant={tool.riskLevel === "sensitive_read" ? "secondary" : "outline"}>{tool.riskLevel}</Badge></TableCell>
                      <TableCell><Badge variant={tool.availability === "available" ? "default" : "destructive"}>{tool.availability}</Badge></TableCell>
                      <TableCell className="text-right"><Button variant="outline" size="sm" onClick={() => setSelectedToolId(tool.toolId)}><Eye data-icon="inline-start" />{t("capabilities.view")}</Button></TableCell>
                    </TableRow>
                  ))}</TableBody>
                </Table>
              ) : (
                <p className="py-10 text-center text-sm text-muted-foreground">{t("capabilities.empty")}</p>
              )}
            </CardContent>
          </Card>

          {selectedToolId && (
            <Card>
              <CardHeader>
                <CardTitle>{detailQuery.data?.title ?? t("capabilities.detail")}</CardTitle>
                <CardDescription>{detailQuery.data?.description ?? selectedToolId}</CardDescription>
              </CardHeader>
              <CardContent>
                {detailQuery.isPending ? (
                  <Skeleton className="h-64 w-full" />
                ) : detailQuery.error ? (
                  <QueryError error={detailQuery.error} retry={() => void detailQuery.refetch()} />
                ) : detailQuery.data ? (
                  <div className="grid gap-5">
                    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                      <DetailField label={t("capabilities.requiredScopes")} value={detailQuery.data.permission.requiredScopes.join(", ") || "—"} />
                      <DetailField label={t("capabilities.resources")} value={detailQuery.data.permission.resourceTypes.join(", ") || "—"} />
                      <DetailField label={t("capabilities.timeout")} value={`${formatNumber(detailQuery.data.execution.timeoutMs)} ms`} />
                      <DetailField label={t("capabilities.auditMode")} value={detailQuery.data.audit.mode} />
                    </div>
                    <Tabs defaultValue="input">
                      <TabsList variant="line">
                        <TabsTrigger value="input">{t("capabilities.inputSchema")}</TabsTrigger>
                        <TabsTrigger value="output">{t("capabilities.outputSchema")}</TabsTrigger>
                      </TabsList>
                      <TabsContent value="input" className="pt-3"><SchemaBlock schema={detailQuery.data.inputSchema} /></TabsContent>
                      <TabsContent value="output" className="pt-3"><SchemaBlock schema={detailQuery.data.outputSchema} /></TabsContent>
                    </Tabs>
                  </div>
                ) : null}
              </CardContent>
            </Card>
          )}
        </>
      )}
    </div>
  )
}

function DetailField({ label, value }: { label: string; value: string }) {
  return <div className="rounded-lg border bg-muted/20 p-3"><div className="text-xs text-muted-foreground">{label}</div><div className="mt-1 break-words font-mono text-xs">{value}</div></div>
}

function SchemaBlock({ schema }: { schema: Record<string, unknown> }) {
  return <pre className="max-h-[32rem] overflow-auto rounded-lg border bg-muted/40 p-4 text-xs leading-5"><code>{JSON.stringify(schema, null, 2)}</code></pre>
}
