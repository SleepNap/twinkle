import { Code2, DatabaseZap, Power, RadioTower, RefreshCw, RotateCcw, ScrollText, TriangleAlert } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import { adminApi, adminQueryKeys, type ClusterShutdownStatus, type RestartPhaseResponse } from "@/api/admin"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useI18n, type MessageKey } from "@/i18n"

const phaseLabelKeys: Record<RestartPhaseResponse["phase"], MessageKey> = {
  RUNNING: "phase.RUNNING", DRAINING: "phase.DRAINING", FLUSH_DIRTY: "phase.FLUSH_DIRTY",
  RESTARTING: "phase.RESTARTING", RESTORED: "phase.RESTORED", FAILED: "phase.FAILED",
}

const clusterPhaseLabelKeys: Record<ClusterShutdownStatus["phase"], MessageKey> = {
  RUNNING: "clusterPhase.RUNNING",
  DRAINING_CHANNELS: "clusterPhase.DRAINING_CHANNELS",
  TERMINATING_CHANNELS: "clusterPhase.TERMINATING_CHANNELS",
  STOPPING_COORDINATOR: "clusterPhase.STOPPING_COORDINATOR",
  PARTIAL_FAILURE: "clusterPhase.PARTIAL_FAILURE",
}

export function OperationsPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<"scripts" | "logic" | "wz" | "netty" | "restart" | "shutdown" | null>(null)
  const inFlight = useQuery({
    queryKey: adminQueryKeys.inFlight,
    queryFn: ({ signal }) => adminApi.inFlight(signal),
    refetchInterval: 5_000,
  })
  const restartPhase = useQuery({
    queryKey: adminQueryKeys.restartPhase,
    queryFn: ({ signal }) => adminApi.restartPhase(signal),
    refetchInterval: 2_000,
  })
  const scriptsMutation = useMutation({
    mutationFn: (reason: string) => adminApi.reloadScripts(reason),
    onSuccess: ({ changed }) => {
      toast.success(t("operations.scriptsSuccess"), {
        description: t("operations.scriptsSuccessDescription", { count: changed }),
      })
      setDialog(null)
    },
    onError: (error) => toast.error(t("operations.scriptsFailed"), { description: error.message }),
  })
  const logicMutation = useMutation({
    mutationFn: (reason: string) => adminApi.reloadLogic(reason),
    onSuccess: (result) => {
      toast.success(t("operations.logicSuccess"), {
        description: t("operations.logicSuccessDescription", {
          version: result.newVersion, safe: result.safeSwitched, interrupted: result.interrupted,
        }),
      })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.inFlight })
      setDialog(null)
    },
    onError: (error) => toast.error(t("operations.logicFailed"), { description: error.message }),
  })
  const gameNetworkStatus = useQuery({
    queryKey: adminQueryKeys.gameNetworkStatus,
    queryFn: ({ signal }) => adminApi.gameNetworkStatus(signal),
    refetchInterval: 2_000,
  })
  const clusterShutdownStatus = useQuery({
    queryKey: adminQueryKeys.clusterShutdownStatus,
    queryFn: ({ signal }) => adminApi.clusterShutdownStatus(signal),
    refetchInterval: 2_000,
  })
  const wzMutation = useMutation({
    mutationFn: (reason: string) => adminApi.reloadWz(reason),
    onSuccess: (result) => {
      const runtimeCount = Object.values(result.runtimeObjects).reduce((sum, count) => sum + count, 0)
      toast.success(t("operations.wzSuccess"), {
        description: t("operations.wzSuccessDescription", {
          version: result.version,
          resources: Object.keys(result.resources).length,
          runtime: runtimeCount,
        }),
      })
      setDialog(null)
    },
    onError: (error) => toast.error(t("operations.wzFailed"), { description: error.message }),
  })
  const restartMutation = useMutation({
    mutationFn: (reason: string) => adminApi.restart(reason),
    onSuccess: ({ phase }) => {
      toast.success(t("operations.restartAccepted"), {
        description: t("operations.restartAcceptedDescription", { phase: t(phaseLabelKeys[phase]) }),
      })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.restartPhase })
      setDialog(null)
    },
    onError: (error) => toast.error(t("operations.restartFailed"), { description: error.message }),
  })
  const nettyRestartMutation = useMutation({
    mutationFn: (reason: string) => adminApi.restartGameNetwork(reason),
    onSuccess: () => {
      toast.success(t("operations.nettyRestartAccepted"), {
        description: t("operations.nettyRestartAcceptedDescription"),
      })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.gameNetworkStatus })
      setDialog(null)
    },
    onError: (error) => toast.error(t("operations.nettyRestartFailed"), { description: error.message }),
  })
  const clusterShutdownMutation = useMutation({
    mutationFn: ({ reason, force }: { reason: string; force: boolean }) => adminApi.shutdownCluster(reason, force),
    onSuccess: () => {
      toast.success(t("operations.shutdownAccepted"), {
        description: t("operations.shutdownAcceptedDescription"),
      })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.clusterShutdownStatus })
      setDialog(null)
    },
    onError: (error) => toast.error(t("operations.shutdownFailed"), { description: error.message }),
  })

  const firstError = inFlight.error ?? restartPhase.error ?? gameNetworkStatus.error ?? clusterShutdownStatus.error
  const isRefreshing = inFlight.isFetching || restartPhase.isFetching || gameNetworkStatus.isFetching
    || clusterShutdownStatus.isFetching

  function refreshAll() {
    void inFlight.refetch()
    void restartPhase.refetch()
    void gameNetworkStatus.refetch()
    void clusterShutdownStatus.refetch()
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("operations.title")}
        description={t("operations.description")}
        action={
          <Button variant="outline" size="sm" onClick={refreshAll} disabled={isRefreshing}>
            <RefreshCw data-icon="inline-start" className={isRefreshing ? "animate-spin" : undefined} />
            {t("operations.refresh")}
          </Button>
        }
      />

      <Alert>
        <TriangleAlert />
        <AlertTitle>{t("operations.warningTitle")}</AlertTitle>
        <AlertDescription>{t("operations.warningDescription")}</AlertDescription>
      </Alert>

      {firstError && <QueryError error={firstError} retry={refreshAll} />}

      <section className="grid gap-3 md:grid-cols-2 xl:grid-cols-3" aria-label={t("operations.actions")}>
        <Card>
          <CardHeader>
            <CardTitle>{t("operations.scriptTitle")}</CardTitle>
            <CardDescription>{t("operations.scriptDescription")}</CardDescription>
            <CardAction><ScrollText className="size-4 text-muted-foreground" /></CardAction>
          </CardHeader>
          <CardContent>
            <ConfirmationDialog
              open={dialog === "scripts"}
              onOpenChange={(open) => setDialog(open ? "scripts" : null)}
              trigger={<Button variant="outline" className="w-full">{t("operations.reloadScripts")}</Button>}
              title={t("operations.reloadScriptsTitle")}
              description={t("operations.reloadScriptsDescription")}
              confirmLabel={t("operations.confirmReload")}
              pending={scriptsMutation.isPending}
              requireReason
              onConfirm={(reason) => scriptsMutation.mutate(reason)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.wzTitle")}</CardTitle>
            <CardDescription>{t("operations.wzDescription")}</CardDescription>
            <CardAction><DatabaseZap className="size-4 text-muted-foreground" /></CardAction>
          </CardHeader>
          <CardContent>
            <ConfirmationDialog
              key={dialog === "shutdown" ? "shutdown-open" : "shutdown-closed"}
              open={dialog === "wz"}
              onOpenChange={(open) => setDialog(open ? "wz" : null)}
              trigger={<Button variant="outline" className="w-full">{t("operations.reloadWz")}</Button>}
              title={t("operations.reloadWzTitle")}
              description={t("operations.reloadWzDescription")}
              confirmLabel={t("operations.confirmReload")}
              pending={wzMutation.isPending}
              requireReason
              onConfirm={(reason) => wzMutation.mutate(reason)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.logicTitle")}</CardTitle>
            <CardDescription>{t("operations.logicDescription")}</CardDescription>
            <CardAction><Code2 className="size-4 text-muted-foreground" /></CardAction>
          </CardHeader>
          <CardContent>
            <ConfirmationDialog
              open={dialog === "logic"}
              onOpenChange={(open) => setDialog(open ? "logic" : null)}
              trigger={<Button variant="outline" className="w-full">{t("operations.reloadLogic")}</Button>}
              title={t("operations.reloadLogicTitle")}
              description={t("operations.reloadLogicDescription", { count: inFlight.data?.inFlightCount ?? 0 })}
              confirmLabel={t("operations.confirmReload")}
              pending={logicMutation.isPending}
              requireReason
              onConfirm={(reason) => logicMutation.mutate(reason)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.nettyRestartTitle")}</CardTitle>
            <CardDescription>{t("operations.nettyRestartDescription")}</CardDescription>
            <CardAction><RadioTower className="size-4 text-amber-600" /></CardAction>
          </CardHeader>
          <CardContent>
            <ConfirmationDialog
              open={dialog === "netty"}
              onOpenChange={(open) => setDialog(open ? "netty" : null)}
              trigger={<Button variant="outline" className="w-full">{t("operations.restartNetty")}</Button>}
              title={t("operations.restartNettyTitle")}
              description={t("operations.restartNettyDescription")}
              confirmLabel={t("operations.confirmRestart")}
              pending={nettyRestartMutation.isPending}
              requireReason
              onConfirm={(reason) => nettyRestartMutation.mutate(reason)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.restartTitle")}</CardTitle>
            <CardDescription>{t("operations.restartDescription")}</CardDescription>
            <CardAction><RotateCcw className="size-4 text-destructive" /></CardAction>
          </CardHeader>
          <CardContent>
            <ConfirmationDialog
              open={dialog === "restart"}
              onOpenChange={(open) => setDialog(open ? "restart" : null)}
              trigger={<Button variant="destructive" className="w-full">{t("operations.requestRestart")}</Button>}
              title={t("operations.requestRestartTitle")}
              description={t("operations.requestRestartDescription")}
              confirmLabel={t("operations.confirmRestart")}
              destructive
              pending={restartMutation.isPending}
              requireReason
              onConfirm={(reason) => restartMutation.mutate(reason)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.shutdownTitle")}</CardTitle>
            <CardDescription>{t("operations.shutdownDescription")}</CardDescription>
            <CardAction><Power className="size-4 text-destructive" /></CardAction>
          </CardHeader>
          <CardContent>
            <ConfirmationDialog
              open={dialog === "shutdown"}
              onOpenChange={(open) => setDialog(open ? "shutdown" : null)}
              trigger={<Button variant="destructive" className="w-full">{t("operations.requestShutdown")}</Button>}
              title={t("operations.requestShutdownTitle")}
              description={t("operations.requestShutdownDescription")}
              confirmLabel={t("operations.confirmShutdown")}
              destructive
              pending={clusterShutdownMutation.isPending}
              requireReason
              forceOption={{
                label: t("shutdown.forceLabel"),
                description: t("shutdown.forceDescription"),
              }}
              onConfirm={(reason, force) => clusterShutdownMutation.mutate({ reason, force })}
            />
          </CardContent>
        </Card>
      </section>

      <section className="grid gap-3 lg:grid-cols-4">
        <Card>
          <CardHeader>
            <CardTitle>{t("operations.nettyStatusTitle")}</CardTitle>
            <CardDescription>{t("operations.nettyStatusDescription")}</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-2 text-sm">
            {gameNetworkStatus.isPending ? (
              <Skeleton className="h-7 w-24" />
            ) : gameNetworkStatus.data ? (
              <>
                <Badge variant={gameNetworkStatus.data.phase === "FAILED" ? "destructive" : "secondary"}>
                  {gameNetworkStatus.data.phase}
                </Badge>
                <span>{t("operations.loginPortStatus", {
                  port: gameNetworkStatus.data.loginPort,
                  state: gameNetworkStatus.data.loginRunning ? t("operations.running") : t("operations.stopped"),
                })}</span>
                <span>{t("operations.channelPortStatus", {
                  id: gameNetworkStatus.data.channelId,
                  port: gameNetworkStatus.data.channelPort,
                  state: gameNetworkStatus.data.channelRunning ? t("operations.running") : t("operations.stopped"),
                })}</span>
              </>
            ) : (
              <span className="text-muted-foreground">{t("operations.phaseUnavailable")}</span>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.clusterStatusTitle")}</CardTitle>
            <CardDescription>{t("operations.clusterStatusDescription")}</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-2 text-sm">
            {clusterShutdownStatus.isPending ? (
              <Skeleton className="h-7 w-24" />
            ) : clusterShutdownStatus.data ? (
              <>
                <Badge variant={clusterShutdownStatus.data.phase === "PARTIAL_FAILURE" ? "destructive" : "secondary"}>
                  {t(clusterPhaseLabelKeys[clusterShutdownStatus.data.phase])}
                </Badge>
                <span>{t("operations.clusterProgress", {
                  completed: clusterShutdownStatus.data.completedCount,
                  total: clusterShutdownStatus.data.targetCount,
                })}</span>
                {clusterShutdownStatus.data.failedChannelIds.length > 0 && (
                  <span className="text-destructive">{t("operations.clusterFailedChannels", {
                    ids: clusterShutdownStatus.data.failedChannelIds.join(", "),
                  })}</span>
                )}
                {clusterShutdownStatus.data.error && (
                  <span className="text-destructive">{clusterShutdownStatus.data.error}</span>
                )}
              </>
            ) : (
              <span className="text-muted-foreground">{t("operations.phaseUnavailable")}</span>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.phaseTitle")}</CardTitle>
            <CardDescription>{t("operations.phaseDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            {restartPhase.isPending ? (
              <Skeleton className="h-7 w-24" />
            ) : restartPhase.data ? (
              <Badge variant={restartPhase.data.phase === "FAILED" ? "destructive" : "secondary"}>
                {t(phaseLabelKeys[restartPhase.data.phase])}
              </Badge>
            ) : (
              <span className="text-sm text-muted-foreground">{t("operations.phaseUnavailable")}</span>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("operations.inFlightTitle")}</CardTitle>
            <CardDescription>{t("operations.inFlightDescription")}</CardDescription>
            <CardAction>
              <Badge variant="outline">{t("operations.inFlightCount", { count: inFlight.data?.inFlightCount ?? 0 })}</Badge>
            </CardAction>
          </CardHeader>
          <CardContent>
            {inFlight.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : inFlight.error && !inFlight.data ? (
              <p className="py-6 text-center text-sm text-muted-foreground">{t("operations.inFlightUnavailable")}</p>
            ) : inFlight.data && inFlight.data.entities.length > 0 ? (
              <Table>
                <TableHeader><TableRow><TableHead>{t("operations.entityId")}</TableHead></TableRow></TableHeader>
                <TableBody>
                  {inFlight.data.entities.map((entityId) => (
                    <TableRow key={entityId}>
                      <TableCell className="font-mono text-xs">{entityId}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-6 text-center text-sm text-muted-foreground">{t("operations.noInFlight")}</p>
            )}
          </CardContent>
        </Card>
      </section>
    </div>
  )
}
