import { Pencil, Plus, RefreshCw, Search } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import { adminApi, adminQueryKeys, type AccountOption, type AdminRole } from "@/api/admin"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
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
import { useI18n, type MessageKey } from "@/i18n"

const PERMISSION_OPTIONS: { value: string; key: MessageKey }[] = [
  { value: "admin:read", key: "roles.perm.read" },
  { value: "admin.config:write", key: "roles.perm.configWrite" },
  { value: "admin.player:kick", key: "roles.perm.kick" },
  { value: "admin.reload:logic", key: "roles.perm.reloadLogic" },
  { value: "admin.reload:scripts", key: "roles.perm.reloadScripts" },
  { value: "admin.restart", key: "roles.perm.restart" },
  { value: "admin.task:manage", key: "roles.perm.task" },
  { value: "admin.billing:manage", key: "roles.perm.billing" },
  { value: "admin.role:manage", key: "roles.perm.role" },
  { value: "admin.ai:manage", key: "roles.perm.ai" },
  { value: "admin.account:manage", key: "roles.perm.account" },
]

interface RoleDraft {
  id?: number
  roleCode: string
  displayName: string
  description: string
  permissions: string[]
  reason: string
}

function parsePermissions(value: string): string[] {
  return value ? value.split(",").map((part) => part.trim()).filter(Boolean) : []
}

export function RolesPage() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState<RoleDraft | null>(null)
  const query = useQuery({
    queryKey: adminQueryKeys.roles,
    queryFn: ({ signal }) => adminApi.roles(signal),
  })

  const saveMutation = useMutation({
    mutationFn: (current: RoleDraft) => {
      const permissions = current.permissions.includes("*")
        ? "*"
        : current.permissions.join(",")
      return current.id
        ? adminApi.updateRole(current.id, {
            displayName: current.displayName,
            description: current.description,
            permissions,
          }, current.reason)
        : adminApi.createRole({
            roleCode: current.roleCode,
            displayName: current.displayName,
            description: current.description,
            permissions,
          }, current.reason)
    },
    onSuccess: (_result, current) => {
      toast.success(current.id ? t("roles.updated") : t("roles.created"))
      setDraft(null)
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.roles })
    },
    onError: (error, current) => {
      toast.error(current.id ? t("roles.updateFailed") : t("roles.createFailed"), {
        description: error.message,
      })
    },
  })

  const [accountSearch, setAccountSearch] = useState("")
  const [submittedSearch, setSubmittedSearch] = useState("")
  const [assignTarget, setAssignTarget] = useState<AccountOption | null>(null)
  const [assignRoleIds, setAssignRoleIds] = useState<number[]>([])
  const [assignReason, setAssignReason] = useState("")

  const accountSearchQuery = useQuery({
    queryKey: ["admin", "account-search", submittedSearch],
    queryFn: ({ signal }) => adminApi.searchAccounts(submittedSearch, 20, signal),
    enabled: Boolean(submittedSearch),
    retry: false,
  })
  const assignMutation = useMutation({
    mutationFn: ({ accountId, roleIds, reason }: { accountId: number; roleIds: number[]; reason: string }) =>
      adminApi.setAccountRoles(accountId, roleIds, reason),
    onSuccess: () => {
      toast.success(t("roles.assignSuccess"))
      setAssignTarget(null)
      setAssignReason("")
    },
    onError: (error) => toast.error(t("roles.assignFailed"), { description: error.message }),
  })

  function submitAccountSearch() {
    const normalized = accountSearch.trim()
    if (normalized) setSubmittedSearch(normalized)
  }

  function openAssign(account: AccountOption) {
    setAssignRoleIds([])
    setAssignReason("")
    setAssignTarget(account)
    void queryClient.fetchQuery({
      queryKey: ["admin", "account-roles", account.id],
      queryFn: ({ signal }) => adminApi.accountRoles(account.id, signal),
    }).then((data) => setAssignRoleIds(data.roles.map((role) => role.id)))
  }

  function toggleAssignRole(roleId: number) {
    setAssignRoleIds((current) => current.includes(roleId)
      ? current.filter((id) => id !== roleId)
      : [...current, roleId])
  }

  function openCreate() {
    setDraft({ roleCode: "", displayName: "", description: "", permissions: ["admin:read"], reason: "" })
  }

  function openEdit(role: AdminRole) {
    setDraft({
      id: role.id,
      roleCode: role.roleCode,
      displayName: role.displayName,
      description: role.description,
      permissions: parsePermissions(role.permissions),
      reason: "",
    })
  }

  function togglePermission(value: string) {
    setDraft((current) => {
      if (!current) return current
      if (value === "*") {
        return { ...current, permissions: current.permissions.includes("*") ? [] : ["*"] }
      }
      const withoutWildcard = current.permissions.filter((item) => item !== "*")
      const next = withoutWildcard.includes(value)
        ? withoutWildcard.filter((item) => item !== value)
        : [...withoutWildcard, value]
      return { ...current, permissions: next }
    })
  }

  const canSave = draft
    && (!draft.id ? draft.roleCode.trim().length > 0 : true)
    && draft.reason.trim().length > 0
    && draft.permissions.length > 0

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("roles.title")}
        description={t("roles.description")}
        action={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => void query.refetch()} disabled={query.isFetching}>
              <RefreshCw data-icon="inline-start" className={query.isFetching ? "animate-spin" : undefined} />
              {t("common.refresh")}
            </Button>
            <Button size="sm" onClick={openCreate}>
              <Plus data-icon="inline-start" />{t("roles.create")}
            </Button>
          </div>
        }
      />

      {query.error && <QueryError error={query.error} retry={() => void query.refetch()} />}

      <Card>
        <CardHeader>
          <CardTitle>{t("roles.list")}</CardTitle>
          <CardDescription>{t("roles.listDescription")}</CardDescription>
        </CardHeader>
        <CardContent>
          {query.isPending ? (
            <div className="grid gap-3 py-2">
              {[0, 1, 2].map((row) => <Skeleton key={row} className="h-10 w-full" />)}
            </div>
          ) : query.error && !query.data ? (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("roles.unavailable")}</p>
          ) : query.data && query.data.roles.length > 0 ? (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("roles.roleCode")}</TableHead>
                  <TableHead>{t("roles.displayName")}</TableHead>
                  <TableHead>{t("roles.permissions")}</TableHead>
                  <TableHead className="w-20 text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {query.data.roles.map((role) => (
                  <TableRow key={role.id}>
                    <TableCell>
                      <div className="font-mono text-xs font-medium">{role.roleCode}</div>
                      {role.description && (
                        <div className="text-xs text-muted-foreground">{role.description}</div>
                      )}
                    </TableCell>
                    <TableCell>{role.displayName}</TableCell>
                    <TableCell>
                      <div className="flex max-w-md flex-wrap gap-1">
                        {role.permissions === "*" ? (
                          <Badge>{t("roles.perm.all")}</Badge>
                        ) : parsePermissions(role.permissions).map((permission) => (
                          <Badge key={permission} variant="outline" className="font-mono text-xs">
                            {permission}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button variant="ghost" size="icon-sm" aria-label={t("roles.editLabel", { role: role.roleCode })} onClick={() => openEdit(role)}>
                        <Pencil />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <p className="py-10 text-center text-sm text-muted-foreground">{t("roles.empty")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>{t("roles.assignTitle")}</CardTitle>
          <CardDescription>{t("roles.assignDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="flex max-w-xl gap-2">
            <div className="relative flex-1">
              <Search className="absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-8"
                value={accountSearch}
                onChange={(event) => setAccountSearch(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && submitAccountSearch()}
                placeholder={t("roles.accountSearchPlaceholder")}
                aria-label={t("roles.accountSearchLabel")}
              />
            </div>
            <Button onClick={submitAccountSearch} disabled={!accountSearch.trim()}>{t("roles.search")}</Button>
          </div>
          {accountSearchQuery.data && accountSearchQuery.data.accounts.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("roles.account")}</TableHead>
                  <TableHead className="text-right">{t("common.operation")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {accountSearchQuery.data.accounts.map((account) => (
                  <TableRow key={account.id}>
                    <TableCell>
                      <div className="font-medium">{account.name}</div>
                      <div className="font-mono text-xs text-muted-foreground">ID {account.id}</div>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button variant="ghost" size="sm" onClick={() => openAssign(account)}>{t("roles.assign")}</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog open={draft !== null} onOpenChange={(open) => !open && !saveMutation.isPending && setDraft(null)}>
        <DialogContent showCloseButton={false} className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{draft?.id ? t("roles.edit") : t("roles.createTitle")}</DialogTitle>
            <DialogDescription>{t("roles.dialogDescription")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <div className="grid gap-2">
              <Label htmlFor="role-code">{t("roles.roleCode")}</Label>
              <Input
                id="role-code"
                value={draft?.roleCode ?? ""}
                disabled={Boolean(draft?.id) || saveMutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, roleCode: event.target.value })}
                placeholder="operator"
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="role-display-name">{t("roles.displayName")}</Label>
              <Input
                id="role-display-name"
                value={draft?.displayName ?? ""}
                disabled={saveMutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, displayName: event.target.value })}
              />
            </div>
            <div className="grid gap-2">
              <Label htmlFor="role-description">{t("roles.descriptionLabel")}</Label>
              <Input
                id="role-description"
                value={draft?.description ?? ""}
                disabled={saveMutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, description: event.target.value })}
              />
            </div>
            <div className="grid gap-2">
              <Label>{t("roles.permissions")}</Label>
              <div className="grid gap-2 rounded-lg border p-3">
                <label className="flex items-center gap-2">
                  <Checkbox
                    checked={draft?.permissions.includes("*") ?? false}
                    onCheckedChange={() => togglePermission("*")}
                  />
                  <span className="text-sm font-medium">{t("roles.perm.all")}</span>
                </label>
                <div className="grid gap-1.5 sm:grid-cols-2">
                  {PERMISSION_OPTIONS.map((option) => (
                    <label key={option.value} className="flex items-center gap-2">
                      <Checkbox
                        checked={draft?.permissions.includes(option.value) ?? false}
                        disabled={draft?.permissions.includes("*") ?? false}
                        onCheckedChange={() => togglePermission(option.value)}
                      />
                      <span className="text-sm text-muted-foreground">{t(option.key)}</span>
                    </label>
                  ))}
                </div>
              </div>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="role-reason">{t("auth.reasonLabel")}</Label>
              <Input
                id="role-reason"
                value={draft?.reason ?? ""}
                disabled={saveMutation.isPending}
                onChange={(event) => setDraft((current) => current && { ...current, reason: event.target.value })}
                placeholder={t("auth.reasonPlaceholder")}
              />
            </div>
          </div>
          <DialogFooter>
            {!saveMutation.isPending && (
              <DialogClose asChild><Button variant="outline">{t("common.cancel")}</Button></DialogClose>
            )}
            <Button onClick={() => draft && saveMutation.mutate(draft)} disabled={!canSave || saveMutation.isPending}>
              {saveMutation.isPending ? t("common.processing") : t("roles.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={assignTarget !== null} onOpenChange={(open) => !open && !assignMutation.isPending && setAssignTarget(null)}>
        <DialogContent showCloseButton={false} className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{t("roles.assignDialogTitle", { name: assignTarget?.name ?? "" })}</DialogTitle>
            <DialogDescription>{t("roles.assignDialogDescription")}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <div className="grid gap-2 rounded-lg border p-3">
              {query.data?.roles.map((role) => (
                <label key={role.id} className="flex items-center gap-2">
                  <Checkbox
                    checked={assignRoleIds.includes(role.id)}
                    onCheckedChange={() => toggleAssignRole(role.id)}
                  />
                  <span className="text-sm">{role.displayName || role.roleCode}</span>
                  <span className="font-mono text-xs text-muted-foreground">{role.roleCode}</span>
                </label>
              ))}
            </div>
            <div className="grid gap-2">
              <Label htmlFor="assign-reason">{t("auth.reasonLabel")}</Label>
              <Input
                id="assign-reason"
                value={assignReason}
                onChange={(event) => setAssignReason(event.target.value)}
                placeholder={t("auth.reasonPlaceholder")}
              />
            </div>
          </div>
          <DialogFooter>
            {!assignMutation.isPending && (
              <DialogClose asChild><Button variant="outline">{t("common.cancel")}</Button></DialogClose>
            )}
            <Button
              onClick={() => assignTarget && assignMutation.mutate({
                accountId: assignTarget.id,
                roleIds: assignRoleIds,
                reason: assignReason.trim(),
              })}
              disabled={assignMutation.isPending || !assignReason.trim()}
            >
              {assignMutation.isPending ? t("common.processing") : t("roles.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
