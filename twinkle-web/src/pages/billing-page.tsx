import { RefreshCw } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import { billingApi, billingQueryKeys, type BillingAccountSummary, type SubscriptionPlan } from "@/api/billing"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
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
import { cn } from "@/lib/utils"
import { useI18n, type MessageKey } from "@/i18n"

const REASON_LABELS: Record<string, MessageKey> = {
  ai_consume: "billing.reason.ai_consume",
  websearch_consume: "billing.reason.websearch_consume",
  purchase_nx: "billing.reason.purchase_nx",
  purchase_meso: "billing.reason.purchase_meso",
  daily_signin: "billing.reason.daily_signin",
  admin_adjust: "billing.reason.admin_adjust",
}

export function BillingPage() {
  const { locale, t, formatNumber } = useI18n()
  const queryClient = useQueryClient()
  const [selectedAccount, setSelectedAccount] = useState<BillingAccountSummary | null>(null)
  const [adjustTarget, setAdjustTarget] = useState<BillingAccountSummary | null>(null)
  const [adjustAmount, setAdjustAmount] = useState("")
  const [adjustReason, setAdjustReason] = useState("")
  const [planAction, setPlanAction] = useState<{ account: BillingAccountSummary; planId: number | null } | null>(null)

  const accountsQuery = useQuery({
    queryKey: billingQueryKeys.accounts,
    queryFn: ({ signal }) => billingApi.accounts(signal),
  })
  const plansQuery = useQuery({
    queryKey: billingQueryKeys.plans,
    queryFn: ({ signal }) => billingApi.plans(signal),
  })
  const ratesQuery = useQuery({
    queryKey: billingQueryKeys.rates,
    queryFn: ({ signal }) => billingApi.modelRates(signal),
  })
  const transactionsQuery = useQuery({
    queryKey: billingQueryKeys.transactions(selectedAccount?.accountId ?? 0),
    queryFn: ({ signal }) => billingApi.transactions(selectedAccount!.accountId, signal),
    enabled: Boolean(selectedAccount),
  })

  const adjustMutation = useMutation({
    mutationFn: () => billingApi.adjust(adjustTarget!.accountId, Number(adjustAmount), adjustReason.trim(), adjustReason.trim()),
    onSuccess: () => {
      toast.success(t("billing.adjusted"))
      setAdjustTarget(null)
      setAdjustAmount("")
      setAdjustReason("")
      void queryClient.invalidateQueries({ queryKey: billingQueryKeys.accounts })
      if (selectedAccount) {
        void queryClient.invalidateQueries({ queryKey: billingQueryKeys.transactions(selectedAccount.accountId) })
      }
    },
    onError: (error) => toast.error(t("billing.adjustFailed"), { description: error.message }),
  })
  const planMutation = useMutation({
    mutationFn: (args: { accountId: number; planId: number | null; reason: string }) =>
      billingApi.setPlan(args.accountId, args.planId, args.reason),
    onSuccess: () => {
      toast.success(t("billing.planSet"))
      void queryClient.invalidateQueries({ queryKey: billingQueryKeys.accounts })
    },
    onError: (error) => toast.error(t("billing.planSetFailed"), { description: error.message }),
  })

  function planOf(account: BillingAccountSummary): string {
    if (account.planId == null) return t("billing.noPlan")
    return plansQuery.data?.plans.find((plan) => plan.id === account.planId)?.displayName ?? `#${account.planId}`
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("billing.title")}
        description={t("billing.description")}
        action={
          <Button variant="outline" size="sm" onClick={() => void accountsQuery.refetch()} disabled={accountsQuery.isFetching}>
            <RefreshCw data-icon="inline-start" className={accountsQuery.isFetching ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      {accountsQuery.error && <QueryError error={accountsQuery.error as Error} retry={() => void accountsQuery.refetch()} />}

      <Card>
        <CardHeader>
          <CardTitle>{t("billing.accountsTitle")}</CardTitle>
          <CardDescription>{t("billing.accountsDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          {accountsQuery.isPending ? (
            <div className="grid gap-3 py-2">{[0, 1, 2].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
          ) : (accountsQuery.data?.accounts ?? []).length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("billing.account")}</TableHead>
                  <TableHead className="text-right">{t("billing.balance")}</TableHead>
                  <TableHead>{t("billing.plan")}</TableHead>
                  <TableHead className="text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {accountsQuery.data!.accounts.map((account) => (
                  <TableRow key={account.accountId}>
                    <TableCell>
                      <div className="font-medium">{account.name}</div>
                      <div className="font-mono text-xs text-muted-foreground">ID {account.accountId}</div>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{formatNumber(account.balance)}</TableCell>
                    <TableCell><Badge variant="outline">{planOf(account)}</Badge></TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => setAdjustTarget(account)}>{t("billing.adjust")}</Button>
                        <Button variant="ghost" size="sm" onClick={() => setSelectedAccount(account)}>{t("billing.transactions")}</Button>
                        <PlanSelect
                          account={account}
                          plans={plansQuery.data?.plans ?? []}
                          onSelect={(planId) => setPlanAction({ account, planId })}
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("billing.unavailable")}</p>
          )}
        </CardContent>
      </Card>

      {selectedAccount && (
        <Card>
          <CardHeader>
            <CardTitle>{t("billing.transactions")} — {selectedAccount.name}</CardTitle>
            <CardDescription>ID {selectedAccount.accountId}</CardDescription>
          </CardHeader>
          <CardContent>
            {transactionsQuery.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : (transactionsQuery.data?.transactions ?? []).length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("billing.time")}</TableHead>
                    <TableHead>{t("billing.reason")}</TableHead>
                    <TableHead className="text-right">{t("billing.changeAmount")}</TableHead>
                    <TableHead className="text-right">{t("billing.balanceAfter")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {transactionsQuery.data!.transactions.map((tx) => (
                    <TableRow key={tx.id}>
                      <TableCell className="text-xs text-muted-foreground">{formatDate(tx.createdAt, locale)}</TableCell>
                      <TableCell>{reasonLabel(tx.reason, t)}</TableCell>
                      <TableCell className={cn("text-right tabular-nums", tx.changeAmount > 0 ? "text-emerald-600" : "text-destructive")}>
                        {tx.changeAmount > 0 ? "+" : ""}{formatNumber(tx.changeAmount)}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(tx.balanceAfter)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-8 text-center text-sm text-muted-foreground">{t("billing.transactionsEmpty")}</p>
            )}
          </CardContent>
        </Card>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{t("billing.plansTitle")}</CardTitle>
            <CardDescription>{t("billing.plansDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            {plansQuery.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : (plansQuery.data?.plans ?? []).length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("billing.planCode")}</TableHead>
                    <TableHead className="text-right">{t("billing.monthlyLimit")}</TableHead>
                    <TableHead className="text-right">{t("billing.weeklyLimit")}</TableHead>
                    <TableHead className="text-right">{t("billing.fiveHourLimit")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {plansQuery.data!.plans.map((plan) => (
                    <TableRow key={plan.id}>
                      <TableCell>
                        <div className="font-medium">{plan.displayName}</div>
                        <div className="font-mono text-xs text-muted-foreground">{plan.planCode}</div>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(plan.monthlyLimit)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(plan.weeklyLimit)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(plan.fiveHourLimit)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-8 text-center text-sm text-muted-foreground">{t("billing.unavailable")}</p>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("billing.ratesTitle")}</CardTitle>
            <CardDescription>{t("billing.ratesDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            {ratesQuery.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : (ratesQuery.data?.rates ?? []).length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("billing.modelKey")}</TableHead>
                    <TableHead className="text-right">{t("billing.inputRate")}</TableHead>
                    <TableHead className="text-right">{t("billing.outputRate")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {ratesQuery.data!.rates.map((rate) => (
                    <TableRow key={rate.id}>
                      <TableCell className="font-mono text-xs">{rate.modelKey}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(rate.inputRate)}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(rate.outputRate)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-8 text-center text-sm text-muted-foreground">{t("billing.unavailable")}</p>
            )}
          </CardContent>
        </Card>
      </div>

      <AdjustDialog
        target={adjustTarget}
        amount={adjustAmount}
        reason={adjustReason}
        pending={adjustMutation.isPending}
        onAmountChange={setAdjustAmount}
        onReasonChange={setAdjustReason}
        onClose={() => setAdjustTarget(null)}
        onConfirm={() => adjustMutation.mutate()}
      />

      <ConfirmationDialog
        open={planAction !== null}
        onOpenChange={(open) => !open && setPlanAction(null)}
        title={t("billing.setPlan")}
        description={planAction ? `${planAction.account.name}` : ""}
        confirmLabel={t("billing.setPlan")}
        pending={planMutation.isPending}
        requireReason
        onConfirm={(reason) => planAction && planMutation.mutate({
          accountId: planAction.account.accountId,
          planId: planAction.planId,
          reason,
        })}
      />
    </div>
  )
}

function PlanSelect({ account, plans, onSelect }: {
  account: BillingAccountSummary
  plans: SubscriptionPlan[]
  onSelect: (planId: number | null) => void
}) {
  const { t } = useI18n()
  return (
    <select
      className="rounded-md border bg-background px-2 py-1 text-xs text-foreground"
      value={account.planId ?? ""}
      onChange={(event) => onSelect(event.target.value ? Number(event.target.value) : null)}
      aria-label={t("billing.setPlan")}
    >
      <option value="">{t("billing.noPlan")}</option>
      {plans.map((plan) => <option key={plan.id} value={plan.id}>{plan.displayName}</option>)}
    </select>
  )
}

function AdjustDialog({ target, amount, reason, pending, onAmountChange, onReasonChange, onClose, onConfirm }: {
  target: BillingAccountSummary | null
  amount: string
  reason: string
  pending: boolean
  onAmountChange: (value: string) => void
  onReasonChange: (value: string) => void
  onClose: () => void
  onConfirm: () => void
}) {
  const { t } = useI18n()
  const validAmount = amount.trim() !== "" && !Number.isNaN(Number(amount)) && reason.trim() !== ""
  return (
    <Dialog open={target !== null} onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent showCloseButton={false} className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("billing.adjustTitle", { name: target?.name ?? "" })}</DialogTitle>
        </DialogHeader>
        <div className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="adjust-amount">{t("billing.adjustAmount")}</Label>
            <Input id="adjust-amount" type="number" value={amount} onChange={(event) => onAmountChange(event.target.value)} />
          </div>
          <div className="grid gap-2">
            <Label htmlFor="adjust-reason">{t("billing.adjustReason")}</Label>
            <Input id="adjust-reason" value={reason} onChange={(event) => onReasonChange(event.target.value)} />
          </div>
        </div>
        <DialogFooter>
          {!pending && <DialogClose asChild><Button variant="outline">{t("common.cancel")}</Button></DialogClose>}
          <Button onClick={onConfirm} disabled={pending || !validAmount}>
            {t("billing.adjustConfirm")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function reasonLabel(reason: string, t: (key: MessageKey) => string) {
  const key = REASON_LABELS[reason]
  return key ? t(key) : reason
}

function formatDate(value: string | null, locale: string) {
  if (!value) return "—"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(date)
}
