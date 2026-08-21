import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Search } from "lucide-react"
import { useEffect, useMemo, useRef, useState } from "react"
import { toast } from "sonner"

import {
  adminApi,
  adminQueryKeys,
  type OnlinePlayer,
  type PacketTraceDirection,
  type PacketTraceEvent,
  type PacketTraceFilterMode,
  type PacketTraceSnapshot,
} from "@/api/admin"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useI18n } from "@/i18n"

interface PacketTraceDialogProps {
  player: OnlinePlayer | null
  onOpenChange: (open: boolean) => void
}

export function PacketTraceDialog({ player, onOpenChange }: PacketTraceDialogProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const initializedFor = useRef<number | null>(null)
  const [mode, setMode] = useState<PacketTraceFilterMode>("EXCLUDE")
  const [directions, setDirections] = useState<Set<PacketTraceDirection>>(
    new Set(["INBOUND", "OUTBOUND"]),
  )
  const [selectedOpcodes, setSelectedOpcodes] = useState<Set<string>>(new Set())
  const [opcodeSearch, setOpcodeSearch] = useState("")
  const [reason, setReason] = useState("")
  const [maxPayloadBytes, setMaxPayloadBytes] = useState(4096)
  const [selectedEvent, setSelectedEvent] = useState<PacketTraceEvent | null>(null)
  const open = player !== null

  const catalogQuery = useQuery({
    queryKey: adminQueryKeys.packetTraceCatalog,
    queryFn: ({ signal }) => adminApi.packetTraceCatalog(signal),
    enabled: open,
    staleTime: 60_000,
  })
  const traceQuery = useQuery({
    queryKey: player ? adminQueryKeys.packetTrace(player.characterId) : ["admin", "packet-trace", "closed"],
    queryFn: ({ signal }) => adminApi.packetTrace(player!.characterId, 0, 200, signal),
    enabled: open,
    refetchInterval: (query) => (query.state.data?.enabled ? 1_000 : false),
    retry: false,
  })

  useEffect(() => {
    if (!player || !catalogQuery.data || !traceQuery.data || initializedFor.current === player.characterId) return
    const existing = traceQuery.data.config
    setMode(existing?.mode ?? "EXCLUDE")
    setDirections(new Set(existing?.directions ?? ["INBOUND", "OUTBOUND"]))
    setSelectedOpcodes(new Set(existing?.opcodeNames ?? catalogQuery.data.defaultExcluded))
    setMaxPayloadBytes(existing?.maxPayloadBytes ?? 4096)
    setOpcodeSearch("")
    setReason("")
    setSelectedEvent(null)
    initializedFor.current = player.characterId
  }, [catalogQuery.data, player, traceQuery.data])

  const availableOpcodes = useMemo(() => {
    const keyword = opcodeSearch.trim().toUpperCase()
    const unique = new Set<string>()
    for (const opcode of catalogQuery.data?.opcodes ?? []) {
      if (!opcode.sensitive && directions.has(opcode.direction) && (!keyword || opcode.name.includes(keyword))) {
        unique.add(opcode.name)
      }
    }
    return [...unique].sort()
  }, [catalogQuery.data?.opcodes, directions, opcodeSearch])

  const startMutation = useMutation({
    mutationFn: () => adminApi.startPacketTrace(player!.characterId, {
      mode,
      directions: [...directions],
      opcodes: [...selectedOpcodes],
      maxPayloadBytes,
    }, reason.trim()),
    onSuccess: (snapshot) => {
      setTraceData(snapshot)
      setSelectedEvent(null)
      toast.success(t("packetTrace.started"))
    },
    onError: (error) => toast.error(t("packetTrace.startFailed"), { description: error.message }),
  })
  const stopMutation = useMutation({
    mutationFn: () => adminApi.stopPacketTrace(player!.characterId, reason.trim()),
    onSuccess: (snapshot) => {
      setTraceData(snapshot)
      toast.success(t("packetTrace.stopped"))
    },
    onError: (error) => toast.error(t("packetTrace.stopFailed"), { description: error.message }),
  })

  function setTraceData(snapshot: PacketTraceSnapshot) {
    if (player) queryClient.setQueryData(adminQueryKeys.packetTrace(player.characterId), snapshot)
  }

  function toggleDirection(direction: PacketTraceDirection, checked: boolean) {
    setDirections((current) => {
      const next = new Set(current)
      if (checked) next.add(direction)
      else next.delete(direction)
      return next
    })
  }

  function toggleOpcode(opcode: string, checked: boolean) {
    setSelectedOpcodes((current) => {
      const next = new Set(current)
      if (checked) next.add(opcode)
      else next.delete(opcode)
      return next
    })
  }

  function closeDialog(nextOpen: boolean) {
    if (!nextOpen) {
      if (player) {
        queryClient.removeQueries({ queryKey: adminQueryKeys.packetTrace(player.characterId), exact: true })
      }
      initializedFor.current = null
      onOpenChange(false)
    }
  }

  const snapshot = traceQuery.data
  const canStart = reason.trim().length > 0 && directions.size > 0
    && (mode === "EXCLUDE" || selectedOpcodes.size > 0)
    && maxPayloadBytes >= 64 && maxPayloadBytes <= 16_384

  return (
    <Dialog open={open} onOpenChange={closeDialog}>
      <DialogContent className="max-h-[92vh] overflow-hidden sm:max-w-6xl">
        <DialogHeader>
          <DialogTitle>{t("packetTrace.title", { name: player?.name ?? "—" })}</DialogTitle>
          <DialogDescription>{t("packetTrace.description", { id: player?.characterId ?? "—" })}</DialogDescription>
        </DialogHeader>
        {(catalogQuery.error || traceQuery.error) && (
          <p className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {(catalogQuery.error ?? traceQuery.error)?.message}
          </p>
        )}

        <div className="grid min-h-0 gap-4 lg:grid-cols-[22rem_minmax(0,1fr)]">
          <div className="grid content-start gap-4 overflow-y-auto pr-1">
            <div className="grid gap-2">
              <Label>{t("packetTrace.mode")}</Label>
              <Tabs value={mode} onValueChange={(value) => setMode(value as PacketTraceFilterMode)}>
                <TabsList className="w-full">
                  <TabsTrigger value="EXCLUDE">{t("packetTrace.exclude")}</TabsTrigger>
                  <TabsTrigger value="INCLUDE">{t("packetTrace.include")}</TabsTrigger>
                </TabsList>
              </Tabs>
              <p className="text-xs text-muted-foreground">
                {mode === "EXCLUDE" ? t("packetTrace.excludeHelp") : t("packetTrace.includeHelp")}
              </p>
            </div>

            <div className="grid gap-2">
              <Label>{t("packetTrace.direction")}</Label>
              <div className="flex gap-5">
                {(["INBOUND", "OUTBOUND"] as const).map((direction) => (
                  <Label key={direction} className="font-normal">
                    <Checkbox
                      checked={directions.has(direction)}
                      onCheckedChange={(checked) => toggleDirection(direction, checked === true)}
                    />
                    {t(direction === "INBOUND" ? "packetTrace.inbound" : "packetTrace.outbound")}
                  </Label>
                ))}
              </div>
            </div>

            <div className="grid gap-2">
              <div className="flex items-center justify-between gap-2">
                <Label>{t("packetTrace.opcodes")}</Label>
                <Badge variant="secondary">{t("packetTrace.selected", { count: selectedOpcodes.size })}</Badge>
              </div>
              <div className="relative">
                <Search className="absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  value={opcodeSearch}
                  onChange={(event) => setOpcodeSearch(event.target.value)}
                  placeholder={t("packetTrace.searchOpcode")}
                  className="pl-8"
                />
              </div>
              <div className="grid max-h-48 gap-2 overflow-y-auto rounded-lg border p-3">
                {availableOpcodes.map((opcode) => (
                  <Label key={opcode} className="font-mono text-xs font-normal">
                    <Checkbox
                      checked={selectedOpcodes.has(opcode)}
                      onCheckedChange={(checked) => toggleOpcode(opcode, checked === true)}
                    />
                    {opcode}
                  </Label>
                ))}
                {availableOpcodes.length === 0 && (
                  <p className="py-4 text-center text-xs text-muted-foreground">{t("packetTrace.noOpcodes")}</p>
                )}
              </div>
              <p className="text-xs text-muted-foreground">{t("packetTrace.sensitiveNotice")}</p>
            </div>

            <div className="grid grid-cols-[1fr_8rem] gap-3">
              <div className="grid gap-2">
                <Label htmlFor="packet-trace-reason">{t("packetTrace.reason")}</Label>
                <Input
                  id="packet-trace-reason"
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder={t("packetTrace.reasonPlaceholder")}
                  maxLength={256}
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="packet-trace-limit">{t("packetTrace.maxBytes")}</Label>
                <Input
                  id="packet-trace-limit"
                  type="number"
                  min={64}
                  max={16384}
                  value={maxPayloadBytes}
                  onChange={(event) => setMaxPayloadBytes(Number(event.target.value))}
                />
              </div>
            </div>

            <div className="flex gap-2">
              <Button
                onClick={() => startMutation.mutate()}
                disabled={!canStart || startMutation.isPending}
                className="flex-1"
              >
                {snapshot?.enabled ? t("packetTrace.apply") : t("packetTrace.start")}
              </Button>
              <Button
                variant="outline"
                onClick={() => stopMutation.mutate()}
                disabled={!snapshot?.enabled || !reason.trim() || stopMutation.isPending}
              >
                {t("packetTrace.stop")}
              </Button>
            </div>
          </div>

          <div className="grid min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-3 rounded-lg border p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={snapshot?.enabled ? "default" : "secondary"}>
                {snapshot?.enabled ? t("packetTrace.live") : t("packetTrace.inactive")}
              </Badge>
              <span className="text-xs text-muted-foreground">
                {t("packetTrace.eventCount", { count: snapshot?.events.length ?? 0 })}
              </span>
              {(snapshot?.droppedEvents ?? 0) > 0 && (
                <span className="text-xs text-amber-600">
                  {t("packetTrace.dropped", { count: snapshot?.droppedEvents ?? 0 })}
                </span>
              )}
            </div>

            <div className="min-h-56 overflow-auto rounded-md border">
              <table className="w-full text-xs">
                <thead className="sticky top-0 bg-muted">
                  <tr className="border-b text-left">
                    <th className="px-2 py-2 font-medium">{t("packetTrace.time")}</th>
                    <th className="px-2 py-2 font-medium">{t("packetTrace.direction")}</th>
                    <th className="px-2 py-2 font-medium">Opcode</th>
                    <th className="px-2 py-2 text-right font-medium">{t("packetTrace.bytes")}</th>
                    <th className="px-2 py-2 font-medium">Hex</th>
                  </tr>
                </thead>
                <tbody>
                  {(snapshot?.events ?? []).map((event) => (
                    <tr
                      key={event.sequence}
                      className="cursor-pointer border-b hover:bg-muted/50"
                      onClick={() => setSelectedEvent(event)}
                    >
                      <td className="whitespace-nowrap px-2 py-1.5 tabular-nums">
                        {new Date(event.timestampEpochMillis).toLocaleTimeString()}
                      </td>
                      <td className="px-2 py-1.5">{event.direction === "INBOUND" ? "IN" : "OUT"}</td>
                      <td className="whitespace-nowrap px-2 py-1.5 font-mono">{event.opcodeName}</td>
                      <td className="px-2 py-1.5 text-right tabular-nums">
                        {event.capturedLength}/{event.packetLength}{event.truncated ? "*" : ""}
                      </td>
                      <td className="max-w-72 truncate px-2 py-1.5 font-mono text-muted-foreground">
                        {event.payloadHex}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!traceQuery.isPending && (snapshot?.events.length ?? 0) === 0 && (
                <p className="py-12 text-center text-sm text-muted-foreground">{t("packetTrace.noEvents")}</p>
              )}
            </div>

            <div className="grid gap-1">
              <Label>{t("packetTrace.packetDetail")}</Label>
              <pre className="max-h-32 min-h-16 overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-2 font-mono text-xs">
                {selectedEvent?.payloadHex ?? t("packetTrace.selectEvent")}
              </pre>
            </div>
          </div>
        </div>

        <DialogFooter showCloseButton />
      </DialogContent>
    </Dialog>
  )
}
