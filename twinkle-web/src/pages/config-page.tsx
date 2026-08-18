import { Pencil, Plus, RefreshCw } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useMemo, useState } from "react"
import { toast } from "sonner"

import { adminApi, adminQueryKeys } from "@/api/admin"
import { PageHeader } from "@/components/page-header"
import { EmptyState, QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
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

interface ConfigDraft {
  key: string
  value: string
  existing: boolean
  reason: string
}

export function ConfigPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<ConfigDraft | null>(null)
  const query = useQuery({
    queryKey: adminQueryKeys.config,
    queryFn: ({ signal }) => adminApi.config(signal),
  })
  const entries = useMemo(
    () => Object.entries(query.data?.configs ?? {}).sort(([left], [right]) => left.localeCompare(right)),
    [query.data?.configs],
  )
  const mutation = useMutation({
    mutationFn: ({ key, value, reason }: ConfigDraft) => adminApi.setConfig(key, value, reason),
    onSuccess: (result) => {
      queryClient.setQueryData(adminQueryKeys.config, (current: typeof query.data) => ({
        version: result.version,
        configs: { ...current?.configs, [result.key]: result.value },
      }))
      toast.success(t("config.updated"), {
        description: t("config.updatedDescription", { key: result.key, version: result.version }),
      })
      setDraft(null)
    },
    onError: (error) => toast.error(t("config.updateFailed"), { description: error.message }),
  })

  function saveDraft() {
    if (!draft || !draft.key.trim() || !draft.reason.trim()) return
    mutation.mutate({ ...draft, key: draft.key.trim() })
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("config.title")}
        description={t("config.description")}
        action={
          <div className="flex items-center gap-2">
            {query.data && <Badge variant="secondary">{t("config.version", { version: query.data.version })}</Badge>}
            <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isFetching}>
              <RefreshCw data-icon="inline-start" className={query.isFetching ? "animate-spin" : undefined} />
              {t("common.refresh")}
            </Button>
            <Button size="sm" onClick={() => setDraft({ key: "", value: "", existing: false, reason: "" })}>
              <Plus data-icon="inline-start" />{t("config.add")}
            </Button>
          </div>
        }
      />

      {query.error && <QueryError error={query.error} retry={() => void query.refetch()} />}

      <Card>
        <CardContent>
          {query.isPending ? (
            <div className="grid gap-3 py-2">
              {[0, 1, 2, 3, 4].map((row) => <Skeleton key={row} className="h-10 w-full" />)}
            </div>
          ) : query.error && !query.data ? (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("config.unavailable")}</p>
          ) : entries.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("config.key")}</TableHead>
                  <TableHead>{t("config.value")}</TableHead>
                  <TableHead className="w-20 text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.map(([key, value]) => (
                  <TableRow key={key}>
                    <TableCell className="font-mono text-xs font-medium">{key}</TableCell>
                    <TableCell className="max-w-lg truncate font-mono text-xs text-muted-foreground">{value}</TableCell>
                    <TableCell className="text-right">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={t("config.editLabel", { key })}
                        onClick={() => setDraft({ key, value, existing: true, reason: "" })}
                      >
                        <Pencil />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <EmptyState title={t("config.empty")} description={t("config.emptyDescription")} />
          )}
        </CardContent>
      </Card>

      <Dialog open={draft !== null} onOpenChange={(open) => !open && !mutation.isPending && setDraft(null)}>
        <DialogContent showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>{draft?.existing ? t("config.edit") : t("config.create")}</DialogTitle>
            <DialogDescription>{t("config.dialogDescription")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="config-key">{t("config.key")}</Label>
              <Input
                id="config-key"
                value={draft?.key ?? ""}
                disabled={draft?.existing || mutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, key: event.target.value })}
                placeholder="game.exp.rate"
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="config-value">{t("config.valueLabel")}</Label>
              <Input
                id="config-value"
                value={draft?.value ?? ""}
                disabled={mutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, value: event.target.value })}
                onKeyDown={(event) => event.key === "Enter" && saveDraft()}
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="config-reason">{t("auth.reasonLabel")}</Label>
              <Input
                id="config-reason"
                value={draft?.reason ?? ""}
                disabled={mutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, reason: event.target.value })}
                placeholder={t("auth.reasonPlaceholder")}
              />
            </div>
          </div>
          <DialogFooter>
            {!mutation.isPending && (
              <DialogClose asChild><Button variant="outline">{t("common.cancel")}</Button></DialogClose>
            )}
            <Button onClick={saveDraft} disabled={!draft?.key.trim() || !draft?.reason.trim() || mutation.isPending}>
              {mutation.isPending ? t("config.saving") : t("config.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
