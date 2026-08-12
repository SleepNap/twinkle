import { Copy, KeyRound, Loader2, MoreHorizontal, Plus, RefreshCw, ShieldCheck, Unplug } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useMemo, useState } from "react"
import { toast } from "sonner"

import {
  capabilityApi,
  capabilityQueryKeys,
  supportedScopes,
  type ApiKeySummary,
  type IssuedApiKey,
  type IssueApiKeyInput,
} from "@/api/capability"
import { useCredential } from "@/auth/use-credential"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
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

interface KeyDraft {
  displayName: string
  ownerAccountId: string
  scopes: string[]
  expiresAt: string
}

interface KeyAction {
  kind: "disable" | "enable" | "rotate" | "revoke"
  key: ApiKeySummary
}

interface ScopeDraft {
  key: ApiKeySummary
  scopes: string[]
}

const emptyDraft: KeyDraft = {
  displayName: "",
  ownerAccountId: "",
  scopes: ["game:read"],
  expiresAt: "",
}

export function ApiKeysPage() {
  const { locale, t } = useI18n()
  const { token, connect, disconnect } = useCredential()
  const queryClient = useQueryClient()
  const [candidate, setCandidate] = useState("")
  const [draft, setDraft] = useState<KeyDraft | null>(null)
  const [issuedKey, setIssuedKey] = useState<IssuedApiKey | null>(null)
  const [action, setAction] = useState<KeyAction | null>(null)
  const [scopeDraft, setScopeDraft] = useState<ScopeDraft | null>(null)

  const identityQuery = useQuery({
    queryKey: capabilityQueryKeys.identity,
    queryFn: ({ signal }) => capabilityApi.identity(token, signal),
    enabled: Boolean(token),
    retry: false,
  })
  const keysQuery = useQuery({
    queryKey: capabilityQueryKeys.keys,
    queryFn: ({ signal }) => capabilityApi.keys(token, signal),
    enabled: Boolean(token),
    retry: false,
  })
  const sortedKeys = useMemo(
    () => [...(keysQuery.data ?? [])].sort((left, right) => right.createdAt.localeCompare(left.createdAt)),
    [keysQuery.data],
  )

  const connectMutation = useMutation({
    mutationFn: connect,
    onSuccess: (identity) => {
      toast.success(t("keys.connected"), { description: identity.subject.displayName })
      setCandidate("")
    },
    onError: (error) => toast.error(t("keys.connectFailed"), { description: error.message }),
  })
  const issueMutation = useMutation({
    mutationFn: (input: IssueApiKeyInput) => capabilityApi.issueKey(token, input),
    onSuccess: (result) => {
      setDraft(null)
      setIssuedKey(result)
      void queryClient.invalidateQueries({ queryKey: capabilityQueryKeys.keys })
      toast.success(t("keys.issued"))
    },
    onError: (error) => toast.error(t("keys.issueFailed"), { description: error.message }),
  })
  const actionMutation = useMutation({
    mutationFn: async (current: KeyAction) => {
      if (current.kind === "disable") return capabilityApi.disableKey(token, current.key.keyPrefix)
      if (current.kind === "enable") return capabilityApi.enableKey(token, current.key.keyPrefix)
      if (current.kind === "rotate") return capabilityApi.rotateKey(token, current.key.keyPrefix)
      return capabilityApi.revokeKey(token, current.key.keyPrefix)
    },
    onSuccess: (result) => {
      if (result) setIssuedKey(result)
      toast.success(t("keys.actionCompleted"))
      setAction(null)
      void queryClient.invalidateQueries({ queryKey: capabilityQueryKeys.keys })
    },
    onError: (error) => toast.error(t("keys.actionFailed"), { description: error.message }),
  })
  const scopeMutation = useMutation({
    mutationFn: (current: ScopeDraft) => capabilityApi.updateKeyScopes(token, current.key.keyPrefix, current.scopes),
    onSuccess: () => {
      setScopeDraft(null)
      void queryClient.invalidateQueries({ queryKey: capabilityQueryKeys.keys })
      void queryClient.invalidateQueries({ queryKey: capabilityQueryKeys.identity })
      toast.success(t("keys.scopesUpdated"))
    },
    onError: (error) => toast.error(t("keys.scopeUpdateFailed"), { description: error.message }),
  })

  function submitDraft() {
    if (!draft || !draft.displayName.trim() || draft.scopes.length === 0) return
    let expiresAt: string | null = null
    if (draft.expiresAt) {
      const date = new Date(draft.expiresAt)
      if (Number.isNaN(date.getTime())) {
        toast.error(t("keys.invalidExpiry"))
        return
      }
      expiresAt = date.toISOString()
    }
    issueMutation.mutate({
      displayName: draft.displayName.trim(),
      ownerAccountId: draft.ownerAccountId ? Number(draft.ownerAccountId) : null,
      scopes: draft.scopes,
      expiresAt,
    })
  }

  function toggleScope(scope: string, checked: boolean) {
    setDraft((current) => current && ({
      ...current,
      scopes: checked
        ? [...new Set([...current.scopes, scope])]
        : current.scopes.filter((item) => item !== scope),
    }))
  }

  function clearCredential() {
    disconnect()
    queryClient.removeQueries({ queryKey: ["capability"] })
    toast.success(t("keys.disconnected"))
  }

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("keys.title")}
        description={t("keys.description")}
        action={token ? (
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => void keysQuery.refetch()} disabled={keysQuery.isFetching}>
              <RefreshCw data-icon="inline-start" className={keysQuery.isFetching ? "animate-spin" : undefined} />
              {t("common.refresh")}
            </Button>
            <Button size="sm" onClick={() => setDraft({ ...emptyDraft })}>
              <Plus data-icon="inline-start" />{t("keys.issue")}
            </Button>
          </div>
        ) : undefined}
      />

      {!token ? (
        <Card className="max-w-xl">
          <CardHeader>
            <CardTitle>{t("keys.connectTitle")}</CardTitle>
            <CardDescription>{t("keys.connectDescription")}</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="management-key">{t("keys.managementKey")}</Label>
              <Input
                id="management-key"
                type="password"
                autoComplete="off"
                value={candidate}
                onChange={(event) => setCandidate(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && candidate.trim() && connectMutation.mutate(candidate)}
                placeholder="twk_… / TWINKLE_API_BOOTSTRAP_KEY"
              />
            </div>
            <p className="text-xs leading-5 text-muted-foreground">{t("keys.sessionOnly")}</p>
            <Button
              className="w-fit"
              onClick={() => connectMutation.mutate(candidate)}
              disabled={!candidate.trim() || connectMutation.isPending}
            >
              {connectMutation.isPending && <Loader2 data-icon="inline-start" className="animate-spin" />}
              {t("keys.connect")}
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <Alert>
            <KeyRound />
            <AlertTitle>{identityQuery.data?.subject.displayName ?? t("keys.verifying")}</AlertTitle>
            <AlertDescription className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
              <span>{t("keys.scopeSummary", { count: identityQuery.data?.effectiveScopes.length ?? 0 })}</span>
              <Button variant="outline" size="sm" onClick={clearCredential}>
                <Unplug data-icon="inline-start" />{t("keys.disconnect")}
              </Button>
            </AlertDescription>
          </Alert>

          {(keysQuery.error ?? identityQuery.error) && (
            <QueryError
              error={(keysQuery.error ?? identityQuery.error) as Error}
              retry={() => { void identityQuery.refetch(); void keysQuery.refetch() }}
            />
          )}

          <Card>
            <CardContent>
              {keysQuery.isPending ? (
                <div className="grid gap-3 py-2">{[0, 1, 2, 3].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
              ) : sortedKeys.length > 0 ? (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t("keys.name")}</TableHead>
                      <TableHead>{t("keys.prefix")}</TableHead>
                      <TableHead>{t("keys.scopes")}</TableHead>
                      <TableHead>{t("keys.status")}</TableHead>
                      <TableHead>{t("keys.lastUsed")}</TableHead>
                      <TableHead className="w-16 text-right">{t("common.operation")}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {sortedKeys.map((key) => (
                      <TableRow key={key.credentialId}>
                        <TableCell>
                          <div className="font-medium">{key.displayName}</div>
                          <div className="text-xs text-muted-foreground">{formatDate(key.createdAt, locale)}</div>
                        </TableCell>
                        <TableCell className="font-mono text-xs">twk_{key.keyPrefix}_…</TableCell>
                        <TableCell><div className="flex max-w-sm flex-wrap gap-1">{key.scopes.map((scope) => <Badge key={scope} variant="outline">{scope}</Badge>)}</div></TableCell>
                        <TableCell><KeyStatus keyRecord={key} /></TableCell>
                        <TableCell className="text-xs text-muted-foreground">{formatDate(key.lastUsedAt, locale)}</TableCell>
                        <TableCell className="text-right">
                          {!key.revokedAt && (
                            <DropdownMenu>
                              <DropdownMenuTrigger asChild>
                                <Button variant="ghost" size="icon-sm" aria-label={t("keys.actionsFor", { name: key.displayName })}><MoreHorizontal /></Button>
                              </DropdownMenuTrigger>
                              <DropdownMenuContent align="end">
                                <DropdownMenuItem onSelect={() => setScopeDraft({ key, scopes: [...key.scopes] })}>
                                  <ShieldCheck />{t("keys.editScopes")}
                                </DropdownMenuItem>
                                <DropdownMenuItem onSelect={() => setAction({ kind: key.disabledAt ? "enable" : "disable", key })}>
                                  {key.disabledAt ? t("keys.enable") : t("keys.disable")}
                                </DropdownMenuItem>
                                <DropdownMenuItem onSelect={() => setAction({ kind: "rotate", key })}>{t("keys.rotate")}</DropdownMenuItem>
                                <DropdownMenuSeparator />
                                <DropdownMenuItem variant="destructive" onSelect={() => setAction({ kind: "revoke", key })}>{t("keys.revoke")}</DropdownMenuItem>
                              </DropdownMenuContent>
                            </DropdownMenu>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              ) : keysQuery.error ? (
                <p className="py-10 text-center text-sm text-muted-foreground">{t("keys.unavailable")}</p>
              ) : (
                <p className="py-10 text-center text-sm text-muted-foreground">{t("keys.empty")}</p>
              )}
            </CardContent>
          </Card>
        </>
      )}

      <IssueKeyDialog draft={draft} setDraft={setDraft} pending={issueMutation.isPending} onSubmit={submitDraft} onToggleScope={toggleScope} />
      <IssuedSecretDialog issuedKey={issuedKey} onClose={() => setIssuedKey(null)} />
      <EditScopesDialog
        draft={scopeDraft}
        setDraft={setScopeDraft}
        pending={scopeMutation.isPending}
        onSubmit={() => scopeDraft && scopeMutation.mutate(scopeDraft)}
      />
      <ConfirmationDialog
        open={action !== null}
        onOpenChange={(open) => !open && setAction(null)}
        title={action ? t(`keys.confirm.${action.kind}`) : ""}
        description={action ? t("keys.confirmDescription", { name: action.key.displayName }) : ""}
        confirmLabel={action ? t(`keys.action.${action.kind}`) : ""}
        destructive={action?.kind === "revoke"}
        pending={actionMutation.isPending}
        onConfirm={() => action && actionMutation.mutate(action)}
      />
    </div>
  )
}

function EditScopesDialog({
  draft,
  setDraft,
  pending,
  onSubmit,
}: {
  draft: ScopeDraft | null
  setDraft: React.Dispatch<React.SetStateAction<ScopeDraft | null>>
  pending: boolean
  onSubmit: () => void
}) {
  const { t } = useI18n()
  const toggle = (scope: string, checked: boolean) => setDraft((current) => current && ({
    ...current,
    scopes: checked
      ? [...new Set([...current.scopes, scope])]
      : current.scopes.filter((item) => item !== scope),
  }))
  return (
    <Dialog open={draft !== null} onOpenChange={(open) => !open && !pending && setDraft(null)}>
      <DialogContent showCloseButton={false} className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("keys.editScopesTitle")}</DialogTitle>
          <DialogDescription>{t("keys.editScopesDescription", { name: draft?.key.displayName ?? "" })}</DialogDescription>
        </DialogHeader>
        <fieldset className="grid gap-2 sm:grid-cols-2">
          {supportedScopes.map((scope) => (
            <Label key={scope} className="flex items-center gap-2 rounded-lg border p-2.5 font-normal">
              <Checkbox checked={draft?.scopes.includes(scope)} onCheckedChange={(checked) => toggle(scope, checked === true)} />
              <span className="font-mono text-xs">{scope}</span>
            </Label>
          ))}
        </fieldset>
        <Alert>
          <ShieldCheck />
          <AlertTitle>{t("keys.aiPermission")}</AlertTitle>
          <AlertDescription>{t("keys.aiPermissionDescription")}</AlertDescription>
        </Alert>
        <DialogFooter>
          {!pending && <DialogClose asChild><Button variant="outline">{t("common.cancel")}</Button></DialogClose>}
          <Button onClick={onSubmit} disabled={pending || !draft || draft.scopes.length === 0}>
            {pending && <Loader2 data-icon="inline-start" className="animate-spin" />}{t("keys.saveScopes")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function IssueKeyDialog({
  draft,
  setDraft,
  pending,
  onSubmit,
  onToggleScope,
}: {
  draft: KeyDraft | null
  setDraft: React.Dispatch<React.SetStateAction<KeyDraft | null>>
  pending: boolean
  onSubmit: () => void
  onToggleScope: (scope: string, checked: boolean) => void
}) {
  const { t } = useI18n()
  return (
    <Dialog open={draft !== null} onOpenChange={(open) => !open && !pending && setDraft(null)}>
      <DialogContent showCloseButton={false} className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t("keys.issueTitle")}</DialogTitle>
          <DialogDescription>{t("keys.issueDescription")}</DialogDescription>
        </DialogHeader>
        <div className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="key-display-name">{t("keys.displayName")}</Label>
            <Input id="key-display-name" value={draft?.displayName ?? ""} onChange={(event) => setDraft((current) => current && ({ ...current, displayName: event.target.value }))} />
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="grid gap-2">
              <Label htmlFor="key-owner">{t("keys.ownerAccount")}</Label>
              <Input id="key-owner" type="number" min="1" value={draft?.ownerAccountId ?? ""} onChange={(event) => setDraft((current) => current && ({ ...current, ownerAccountId: event.target.value }))} />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="key-expires">{t("keys.expiresAt")}</Label>
              <Input id="key-expires" type="datetime-local" value={draft?.expiresAt ?? ""} onChange={(event) => setDraft((current) => current && ({ ...current, expiresAt: event.target.value }))} />
            </div>
          </div>
          <fieldset className="grid gap-2">
            <legend className="mb-1 text-sm font-medium">{t("keys.scopes")}</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {supportedScopes.map((scope) => (
                <Label key={scope} className="flex items-center gap-2 rounded-lg border p-2.5 font-normal">
                  <Checkbox checked={draft?.scopes.includes(scope)} onCheckedChange={(checked) => onToggleScope(scope, checked === true)} />
                  <span className="font-mono text-xs">{scope}</span>
                </Label>
              ))}
            </div>
          </fieldset>
        </div>
        <DialogFooter>
          {!pending && <DialogClose asChild><Button variant="outline">{t("common.cancel")}</Button></DialogClose>}
          <Button onClick={onSubmit} disabled={pending || !draft?.displayName.trim() || draft.scopes.length === 0}>
            {pending && <Loader2 data-icon="inline-start" className="animate-spin" />}{t("keys.issue")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function IssuedSecretDialog({ issuedKey, onClose }: { issuedKey: IssuedApiKey | null; onClose: () => void }) {
  const { t } = useI18n()
  async function copyToken() {
    if (!issuedKey) return
    try {
      await navigator.clipboard.writeText(issuedKey.token)
      toast.success(t("keys.copied"))
    } catch {
      toast.error(t("keys.copyFailed"))
    }
  }
  return (
    <Dialog open={issuedKey !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{t("keys.secretTitle")}</DialogTitle>
          <DialogDescription>{t("keys.secretDescription")}</DialogDescription>
        </DialogHeader>
        <div className="rounded-lg border bg-muted p-3 font-mono text-xs break-all select-all">{issuedKey?.token}</div>
        <DialogFooter>
          <DialogClose asChild><Button variant="outline">{t("keys.secretSaved")}</Button></DialogClose>
          <Button onClick={() => void copyToken()}><Copy data-icon="inline-start" />{t("keys.copy")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function KeyStatus({ keyRecord }: { keyRecord: ApiKeySummary }) {
  const { t } = useI18n()
  if (keyRecord.revokedAt) return <Badge variant="destructive">{t("keys.revoked")}</Badge>
  if (keyRecord.disabledAt) return <Badge variant="outline">{t("keys.disabled")}</Badge>
  if (keyRecord.expiresAt && new Date(keyRecord.expiresAt) <= new Date()) return <Badge variant="outline">{t("keys.expired")}</Badge>
  return <Badge variant="secondary">{t("keys.active")}</Badge>
}

function formatDate(value: string | null, locale: string) {
  if (!value) return "—"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(date)
}
