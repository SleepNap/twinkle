import { RefreshCw } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import {
  aiPolicyApi,
  aiPolicyQueryKeys,
  type AiPolicy,
  type AiPolicyDraft,
} from "@/api/ai-policy"
import { billingApi, type AccountOption } from "@/api/billing"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogClose, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useI18n } from "@/i18n"

const EMPTY_DRAFT: AiPolicyDraft = {
  enabled: true,
  allowedModels: "",
  dailyPointLimit: 0,
  dailyCallLimit: 0,
  dailyTokenLimit: 0,
}

export function AiPolicyPage() {
  const { t, formatNumber } = useI18n()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<{ accountId: number; name: string } | null>(null)
  const [draft, setDraft] = useState<AiPolicyDraft>(EMPTY_DRAFT)
  const [reason, setReason] = useState("")
  const [accountQuery, setAccountQuery] = useState("")
  const [accountResults, setAccountResults] = useState<AccountOption[]>([])

  const statusQuery = useQuery({
    queryKey: aiPolicyQueryKeys.status,
    queryFn: ({ signal }) => aiPolicyApi.status(signal),
  })
  const policiesQuery = useQuery({
    queryKey: aiPolicyQueryKeys.policies(null),
    queryFn: ({ signal }) => aiPolicyApi.policies(null, signal),
  })

  const saveMutation = useMutation({
    mutationFn: () => aiPolicyApi.savePolicy(editing!.accountId, draft, reason.trim()),
    onSuccess: (result) => {
      toast.success(t("aiPolicy.saved"), {
        description: t("aiPolicy.refreshedKeys", { count: result.refreshedKeys }),
      })
      closeEditor()
      void queryClient.invalidateQueries({ queryKey: aiPolicyQueryKeys.policies(null) })
    },
    onError: (error) => toast.error(t("aiPolicy.saveFailed"), { description: error.message }),
  })

  function closeEditor(): void {
    setEditing(null)
    setDraft(EMPTY_DRAFT)
    setReason("")
    setAccountQuery("")
    setAccountResults([])
  }

  function editExisting(policy: AiPolicy): void {
    setEditing({ accountId: policy.accountId, name: policy.accountName })
    setDraft({
      enabled: policy.enabled,
      allowedModels: policy.allowedModels,
      dailyPointLimit: policy.dailyPointLimit,
      dailyCallLimit: policy.dailyCallLimit,
      dailyTokenLimit: policy.dailyTokenLimit,
    })
    setReason("")
  }

  async function searchAccounts(query: string): Promise<void> {
    setAccountQuery(query)
    if (query.trim().length === 0) {
      setAccountResults([])
      return
    }
    try {
      const result = await billingApi.searchAccounts(query.trim())
      setAccountResults(result.accounts)
    } catch {
      setAccountResults([])
    }
  }

  const status = statusQuery.data
  const policies = policiesQuery.data?.policies ?? []

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("aiPolicy.title")}
        description={t("aiPolicy.description")}
        action={
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              void statusQuery.refetch()
              void policiesQuery.refetch()
            }}
            disabled={statusQuery.isFetching || policiesQuery.isFetching}
          >
            <RefreshCw data-icon="inline-start" className={statusQuery.isFetching ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      {statusQuery.error && (
        <QueryError error={statusQuery.error as Error} retry={() => void statusQuery.refetch()} />
      )}

      <Card>
        <CardHeader>
          <CardTitle>{t("aiPolicy.statusTitle")}</CardTitle>
          <CardDescription>{t("aiPolicy.statusDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          {statusQuery.isPending ? (
            <div className="grid gap-3 py-2">{[0, 1].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
          ) : status ? (
            <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <StatusItem label={t("aiPolicy.runtimeSwitch")}>
                <Badge variant={status.runtimeEnabled ? "default" : "destructive"}>
                  {status.runtimeEnabled ? t("aiPolicy.on") : t("aiPolicy.off")}
                </Badge>
              </StatusItem>
              <StatusItem label={t("aiPolicy.model")}>
                <span className="font-mono text-sm">{status.model || "—"}</span>
              </StatusItem>
              <StatusItem label={t("aiPolicy.health")}>
                <Badge variant={status.degraded ? "destructive" : status.available ? "default" : "secondary"}>
                  {status.degraded
                    ? t("aiPolicy.degraded")
                    : status.available
                      ? t("aiPolicy.healthy")
                      : t("aiPolicy.unavailable")}
                </Badge>
              </StatusItem>
              <StatusItem label={t("aiPolicy.externalModel")}>
                <span>{status.externalModel ? t("aiPolicy.yes") : t("aiPolicy.no")}</span>
              </StatusItem>
              <StatusItem label={t("aiPolicy.callCount")}>
                <span>{formatNumber(status.callCount)}</span>
              </StatusItem>
              <StatusItem label={t("aiPolicy.consecutiveFailures")}>
                <span>{formatNumber(status.consecutiveFailures)}</span>
              </StatusItem>
              <StatusItem label={t("aiPolicy.globalAllowedModels")}>
                <span className="font-mono text-sm">
                  {status.allowedModels.length > 0 ? status.allowedModels.join(", ") : t("aiPolicy.unrestricted")}
                </span>
              </StatusItem>
              {status.lastError && (
                <StatusItem label={t("aiPolicy.lastError")}>
                  <span className="text-sm text-destructive">{status.lastError}</span>
                  <span className="text-xs text-muted-foreground">{status.lastErrorAt}</span>
                </StatusItem>
              )}
            </dl>
          ) : null}
          <p className="mt-4 text-xs text-muted-foreground">{t("aiPolicy.globalHint")}</p>
        </CardContent>
      </Card>

      {policiesQuery.error && (
        <QueryError error={policiesQuery.error as Error} retry={() => void policiesQuery.refetch()} />
      )}

      <Card>
        <CardHeader>
          <CardTitle>{t("aiPolicy.policiesTitle")}</CardTitle>
          <CardDescription>{t("aiPolicy.policiesDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div>
            <Button
              size="sm"
              onClick={() => {
                setEditing({ accountId: 0, name: "" })
                setDraft(EMPTY_DRAFT)
                setReason("")
              }}
            >
              {t("aiPolicy.addPolicy")}
            </Button>
          </div>
          {policiesQuery.isPending ? (
            <div className="grid gap-3 py-2">{[0, 1, 2].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
          ) : policies.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("aiPolicy.account")}</TableHead>
                  <TableHead>{t("aiPolicy.enabled")}</TableHead>
                  <TableHead>{t("aiPolicy.allowedModels")}</TableHead>
                  <TableHead className="text-right">{t("aiPolicy.dailyCalls")}</TableHead>
                  <TableHead className="text-right">{t("aiPolicy.dailyPoints")}</TableHead>
                  <TableHead className="text-right">{t("aiPolicy.dailyTokens")}</TableHead>
                  <TableHead className="text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {policies.map((policy) => (
                  <TableRow key={policy.accountId}>
                    <TableCell>
                      <div className="font-medium">{policy.accountName || `#${policy.accountId}`}</div>
                      <div className="text-xs text-muted-foreground">#{policy.accountId}</div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={policy.enabled ? "default" : "destructive"}>
                        {policy.enabled ? t("aiPolicy.on") : t("aiPolicy.off")}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {policy.allowedModels || t("aiPolicy.unrestricted")}
                    </TableCell>
                    <TableCell className="text-right">
                      {usageLabel(policy.dailyCallUsed, policy.dailyCallLimit, t("aiPolicy.unlimited"), formatNumber)}
                    </TableCell>
                    <TableCell className="text-right">
                      {usageLabel(policy.dailyPointUsed, policy.dailyPointLimit, t("aiPolicy.unlimited"), formatNumber)}
                    </TableCell>
                    <TableCell className="text-right">
                      {usageLabel(policy.dailyTokenUsed, policy.dailyTokenLimit, t("aiPolicy.unlimited"), formatNumber)}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button variant="outline" size="sm" onClick={() => editExisting(policy)}>
                        {t("aiPolicy.edit")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="py-6 text-center text-sm text-muted-foreground">{t("aiPolicy.emptyPolicies")}</p>
          )}
          <p className="text-xs text-muted-foreground">{t("aiPolicy.noPolicyHint")}</p>
        </CardContent>
      </Card>

      <Dialog open={editing !== null} onOpenChange={(open) => !open && closeEditor()}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("aiPolicy.editTitle")}</DialogTitle>
          </DialogHeader>
          <div className="grid gap-4">
            {editing?.accountId === 0 ? (
              <div className="grid gap-2">
                <Label htmlFor="ai-policy-account">{t("aiPolicy.account")}</Label>
                <Input
                  id="ai-policy-account"
                  value={accountQuery}
                  placeholder={t("aiPolicy.accountSearchPlaceholder")}
                  onChange={(event) => void searchAccounts(event.target.value)}
                />
                {accountResults.length > 0 && (
                  <div className="max-h-40 overflow-y-auto rounded-md border">
                    {accountResults.map((account) => (
                      <button
                        key={account.id}
                        type="button"
                        className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-accent"
                        onClick={() => {
                          setEditing({ accountId: account.id, name: account.name })
                          setAccountResults([])
                          setAccountQuery(account.name)
                        }}
                      >
                        <span>{account.name}</span>
                        <span className="text-xs text-muted-foreground">#{account.id}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            ) : (
              <div className="rounded-md border px-3 py-2 text-sm">
                {editing?.name || `#${editing?.accountId}`}
                <span className="ml-2 text-xs text-muted-foreground">#{editing?.accountId}</span>
              </div>
            )}

            <div className="flex items-center gap-2">
              <input
                id="ai-policy-enabled"
                type="checkbox"
                className="size-4"
                checked={draft.enabled}
                onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}
              />
              <Label htmlFor="ai-policy-enabled">{t("aiPolicy.enabled")}</Label>
            </div>

            <div className="grid gap-2">
              <Label htmlFor="ai-policy-models">{t("aiPolicy.allowedModels")}</Label>
              <Input
                id="ai-policy-models"
                value={draft.allowedModels}
                placeholder="provider/model, provider/model"
                onChange={(event) => setDraft({ ...draft, allowedModels: event.target.value })}
              />
              <p className="text-xs text-muted-foreground">{t("aiPolicy.allowedModelsHint")}</p>
            </div>

            <div className="grid gap-4 sm:grid-cols-3">
              <LimitField
                id="ai-policy-calls"
                label={t("aiPolicy.dailyCallLimit")}
                value={draft.dailyCallLimit}
                onChange={(value) => setDraft({ ...draft, dailyCallLimit: value })}
              />
              <LimitField
                id="ai-policy-points"
                label={t("aiPolicy.dailyPointLimit")}
                value={draft.dailyPointLimit}
                onChange={(value) => setDraft({ ...draft, dailyPointLimit: value })}
              />
              <LimitField
                id="ai-policy-tokens"
                label={t("aiPolicy.dailyTokenLimit")}
                value={draft.dailyTokenLimit}
                onChange={(value) => setDraft({ ...draft, dailyTokenLimit: value })}
              />
            </div>
            <p className="text-xs text-muted-foreground">{t("aiPolicy.limitHint")}</p>

            <div className="grid gap-2">
              <Label htmlFor="ai-policy-reason">{t("auth.reasonLabel")}</Label>
              <Input
                id="ai-policy-reason"
                value={reason}
                placeholder={t("auth.reasonPlaceholder")}
                onChange={(event) => setReason(event.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">{t("common.cancel")}</Button>
            </DialogClose>
            <Button
              onClick={() => saveMutation.mutate()}
              disabled={
                saveMutation.isPending
                || reason.trim().length === 0
                || !editing
                || editing.accountId === 0
              }
            >
              {t("aiPolicy.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function StatusItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1">
      <dt className="text-xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="grid gap-0.5">{children}</dd>
    </div>
  )
}

function LimitField({
  id,
  label,
  value,
  onChange,
}: {
  id: string
  label: string
  value: number
  onChange: (value: number) => void
}) {
  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="number"
        min={0}
        value={value}
        onChange={(event) => onChange(Math.max(0, Number(event.target.value) || 0))}
      />
    </div>
  )
}

/** 限额 0 表示不限制，只显示已用量。 */
function usageLabel(
  used: number,
  limit: number,
  unlimitedLabel: string,
  formatNumber: (value: number) => string,
): string {
  if (limit > 0) {
    return `${formatNumber(used)} / ${formatNumber(limit)}`
  }
  return `${formatNumber(used)} / ${unlimitedLabel}`
}
