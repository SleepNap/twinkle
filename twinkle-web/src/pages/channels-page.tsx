import { RefreshCw } from "lucide-react"
import { useQuery } from "@tanstack/react-query"

import { adminApi, adminQueryKeys } from "@/api/admin"
import { PageHeader } from "@/components/page-header"
import { EmptyState, QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
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

export function ChannelsPage() {
  const { t } = useI18n()
  const query = useQuery({
    queryKey: adminQueryKeys.channels,
    queryFn: ({ signal }) => adminApi.channels(signal),
    refetchInterval: 10_000,
  })

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("channels.title")}
        description={t("channels.description")}
        action={
          <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isFetching}>
            <RefreshCw data-icon="inline-start" className={query.isFetching ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      {query.error && <QueryError error={query.error} retry={() => void query.refetch()} />}

      <Card>
        <CardContent>
          {query.isPending ? (
            <div className="grid gap-3 py-2">
              {[0, 1, 2, 3].map((row) => <Skeleton key={row} className="h-10 w-full" />)}
            </div>
          ) : query.error && !query.data ? (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("channels.unavailable")}</p>
          ) : query.data && query.data.channels.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("channels.id")}</TableHead>
                  <TableHead>{t("channels.status")}</TableHead>
                  <TableHead>{t("channels.host")}</TableHead>
                  <TableHead className="text-right">{t("channels.port")}</TableHead>
                  <TableHead className="text-right">{t("table.onlinePlayers")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {query.data.channels.map((channel) => (
                  <TableRow key={channel.channelId}>
                    <TableCell className="font-medium">{channel.channelId}</TableCell>
                    <TableCell><Badge variant="secondary">{t("channels.registered")}</Badge></TableCell>
                    <TableCell className="font-mono text-xs">{channel.host}</TableCell>
                    <TableCell className="text-right font-mono text-xs">{channel.port}</TableCell>
                    <TableCell className="text-right tabular-nums">{channel.onlineCount}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <EmptyState title={t("channels.empty")} description={t("channels.emptyDescription")} />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
