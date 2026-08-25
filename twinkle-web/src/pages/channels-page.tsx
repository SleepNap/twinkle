import { Play, Power, RefreshCw, Square } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import { adminApi, adminQueryKeys, type Channel } from "@/api/admin"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { EmptyState, QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { useI18n, type MessageKey } from "@/i18n"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

const stateLabelKeys: Record<Channel["state"], MessageKey> = {
  RUNNING: "channels.state.RUNNING",
  STOPPED: "channels.state.STOPPED",
  STARTING: "channels.state.STARTING",
  STOPPING: "channels.state.STOPPING",
  TERMINATING: "channels.state.TERMINATING",
  FAILED: "channels.state.FAILED",
  UNAVAILABLE: "channels.state.UNAVAILABLE",
}

type ChannelAction = { channel: Channel; action: "start" | "stop" | "terminate" }

export function ChannelsPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [pendingAction, setPendingAction] = useState<ChannelAction | null>(null)
  const query = useQuery({
    queryKey: adminQueryKeys.channels,
    queryFn: ({ signal }) => adminApi.channels(signal),
    refetchInterval: 3_000,
  })
  const lifecycleMutation = useMutation({
    mutationFn: ({ channel, action, reason, force }: ChannelAction & { reason: string; force: boolean }) =>
      action === "start" ? adminApi.startChannel(channel.channelId, reason)
        : action === "stop" ? adminApi.stopChannel(channel.channelId, reason, force)
          : adminApi.terminateChannel(channel.channelId, reason, force),
    onSuccess: (result, variables) => {
      const successKey = variables.action === "start" ? "channels.startAccepted"
        : variables.action === "stop" ? "channels.stopAccepted" : "channels.terminateAccepted"
      toast.success(t(successKey), {
        description: t("channels.actionAcceptedDescription", { id: result.status.channelId }),
      })
      setPendingAction(null)
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.channels })
    },
    onError: (error) => toast.error(t("channels.actionFailed"), { description: error.message }),
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
                  <TableHead>{t("channels.topology")}</TableHead>
                  <TableHead>{t("channels.host")}</TableHead>
                  <TableHead className="text-right">{t("channels.port")}</TableHead>
                  <TableHead className="text-right">{t("table.onlinePlayers")}</TableHead>
                  <TableHead className="text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {query.data.channels.map((channel) => {
                  const transitioning = channel.state === "STARTING" || channel.state === "STOPPING"
                    || channel.state === "TERMINATING"
                  return (
                    <TableRow key={channel.channelId}>
                      <TableCell className="font-medium">{channel.channelId}</TableCell>
                      <TableCell>
                        <Badge variant={channel.state === "FAILED" || channel.state === "UNAVAILABLE" ? "destructive" : "secondary"}>
                          {t(stateLabelKeys[channel.state])}
                        </Badge>
                        {channel.error && <p className="mt-1 max-w-48 text-xs text-destructive">{channel.error}</p>}
                      </TableCell>
                      <TableCell>{t(channel.topology === "EMBEDDED" ? "channels.topology.embedded" : "channels.topology.distributed")}</TableCell>
                      <TableCell className="font-mono text-xs">{channel.host || "—"}</TableCell>
                      <TableCell className="text-right font-mono text-xs">{channel.port || "—"}</TableCell>
                      <TableCell className="text-right tabular-nums">{channel.onlineCount}</TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-2">
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={!channel.controllable || transitioning || channel.state === "RUNNING"}
                            onClick={() => setPendingAction({ channel, action: "start" })}
                          >
                            <Play data-icon="inline-start" />
                            {t("channels.start")}
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={!channel.controllable || transitioning || channel.state !== "RUNNING"}
                            onClick={() => setPendingAction({ channel, action: "stop" })}
                          >
                            <Square data-icon="inline-start" />
                            {t("channels.stop")}
                          </Button>
                          {channel.topology !== "EMBEDDED" && (
                            <Button
                              variant="destructive"
                              size="sm"
                              disabled={!channel.controllable || transitioning}
                              onClick={() => setPendingAction({ channel, action: "terminate" })}
                            >
                              <Power data-icon="inline-start" />
                              {t("channels.terminate")}
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          ) : (
            <EmptyState title={t("channels.empty")} description={t("channels.emptyDescription")} />
          )}
        </CardContent>
      </Card>

      <ConfirmationDialog
        key={pendingAction ? `${pendingAction.channel.channelId}-${pendingAction.action}` : "closed"}
        open={pendingAction !== null}
        onOpenChange={(open) => !open && setPendingAction(null)}
        title={t(pendingAction?.action === "terminate" ? "channels.terminateTitle"
          : pendingAction?.action === "stop" ? "channels.stopTitle" : "channels.startTitle", {
          id: pendingAction?.channel.channelId ?? "",
        })}
        description={t(pendingAction?.action === "terminate" ? "channels.terminateDescription"
          : pendingAction?.action === "stop" ? "channels.stopDescription" : "channels.startDescription")}
        confirmLabel={t(pendingAction?.action === "terminate" ? "channels.terminateConfirm"
          : pendingAction?.action === "stop" ? "channels.stopConfirm" : "channels.startConfirm")}
        destructive={pendingAction?.action === "stop" || pendingAction?.action === "terminate"}
        pending={lifecycleMutation.isPending}
        requireReason
        forceOption={pendingAction?.action === "stop" || pendingAction?.action === "terminate" ? {
          label: t("channels.forceLabel"),
          description: t("channels.forceDescription"),
        } : undefined}
        onConfirm={(reason, force) => {
          if (pendingAction) lifecycleMutation.mutate({ ...pendingAction, reason, force })
        }}
      />
    </div>
  )
}
