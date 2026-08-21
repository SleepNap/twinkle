import { Radio, RefreshCw, Search } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useMemo, useState } from "react"
import { toast } from "sonner"

import { adminApi, adminQueryKeys, type OnlinePlayer } from "@/api/admin"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { PacketTraceDialog } from "@/components/packet-trace-dialog"
import { EmptyState, QueryError } from "@/components/query-state"
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
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useI18n } from "@/i18n"

export function PlayersPage() {
  const { t, formatNumber } = useI18n()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [kickTarget, setKickTarget] = useState<OnlinePlayer | null>(null)
  const [traceTarget, setTraceTarget] = useState<OnlinePlayer | null>(null)
  const query = useQuery({
    queryKey: adminQueryKeys.online,
    queryFn: ({ signal }) => adminApi.online(signal),
    refetchInterval: 5_000,
  })
  const filteredPlayers = useMemo(() => {
    const keyword = search.trim().toLocaleLowerCase()
    if (!keyword) return query.data?.players ?? []
    return (query.data?.players ?? []).filter(
      (player) =>
        player.name.toLocaleLowerCase().includes(keyword) ||
        String(player.characterId).includes(keyword),
    )
  }, [query.data?.players, search])
  const kickMutation = useMutation({
    mutationFn: ({ characterId, reason }: { characterId: number; reason: string }) =>
      adminApi.kick(characterId, reason),
    onSuccess: ({ characterId }) => {
      toast.success(t("players.kicked"), { description: t("players.kickDescription", { id: characterId }) })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.online })
      setKickTarget(null)
    },
    onError: (error) => toast.error(t("players.kickFailed"), { description: error.message }),
  })

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("players.title")}
        description={t("players.description")}
        action={
          <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isFetching}>
            <RefreshCw data-icon="inline-start" className={query.isFetching ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      {query.error && <QueryError error={query.error} retry={() => void query.refetch()} />}

      <Card>
        <CardHeader>
          <CardTitle>{t("players.list")}</CardTitle>
          <CardDescription>{t("players.autoUpdate")}</CardDescription>
          <CardAction>
            <Badge variant="secondary">{t("players.onlineCount", {
              count: query.data ? formatNumber(query.data.onlineCount) : "—",
            })}</Badge>
          </CardAction>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="relative max-w-sm">
            <Search className="absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t("players.searchPlaceholder")}
              aria-label={t("players.searchLabel")}
              className="pl-8"
            />
          </div>
          {query.isPending ? (
            <div className="grid gap-3 py-2">
              {[0, 1, 2, 3].map((row) => <Skeleton key={row} className="h-10 w-full" />)}
            </div>
          ) : query.error && !query.data ? (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("players.unavailable")}</p>
          ) : filteredPlayers.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("players.player")}</TableHead>
                  <TableHead>{t("players.characterId")}</TableHead>
                  <TableHead className="text-right">{t("players.level")}</TableHead>
                  <TableHead className="text-right">{t("players.job")}</TableHead>
                  <TableHead className="text-right">{t("players.map")}</TableHead>
                  <TableHead className="w-44 text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredPlayers.map((player) => (
                  <TableRow key={player.characterId}>
                    <TableCell className="font-medium">{player.name}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{player.characterId}</TableCell>
                    <TableCell className="text-right tabular-nums">{player.level}</TableCell>
                    <TableCell className="text-right tabular-nums">{player.job}</TableCell>
                    <TableCell className="text-right tabular-nums">{player.mapId}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => setTraceTarget(player)}>
                          <Radio data-icon="inline-start" />
                          {t("packetTrace.action")}
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => setKickTarget(player)}>
                          {t("players.kick")}
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <EmptyState
              title={search ? t("players.noMatch") : t("players.empty")}
              description={search ? t("players.noMatchDescription") : t("players.emptyDescription")}
            />
          )}
        </CardContent>
      </Card>

      <ConfirmationDialog
        open={kickTarget !== null}
        onOpenChange={(open) => !open && setKickTarget(null)}
        title={t("players.kickTitle", { name: kickTarget?.name ?? t("players.thisPlayer") })}
        description={t("players.kickConfirmDescription", { id: kickTarget?.characterId ?? "—" })}
        confirmLabel={t("players.kickConfirm")}
        destructive
        pending={kickMutation.isPending}
        requireReason
        onConfirm={(reason) => kickTarget && kickMutation.mutate({ characterId: kickTarget.characterId, reason })}
      />
      <PacketTraceDialog player={traceTarget} onOpenChange={(open) => !open && setTraceTarget(null)} />
    </div>
  )
}
