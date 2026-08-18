import { KeyRound, Search, UserRound } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { Link } from "react-router-dom"
import { toast } from "sonner"

import { adminApi, adminQueryKeys } from "@/api/admin"
import { capabilityApi, capabilityQueryKeys, type CharacterSummary } from "@/api/capability"
import { useCredential } from "@/auth/use-credential"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
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

export function AccountsPage() {
  const { t, formatNumber } = useI18n()
  const { token } = useCredential()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [submittedName, setSubmittedName] = useState("")
  const [kickTarget, setKickTarget] = useState<CharacterSummary | null>(null)
  const accountQuery = useQuery({
    queryKey: capabilityQueryKeys.account(submittedName),
    queryFn: ({ signal }) => capabilityApi.account(token, submittedName, signal),
    enabled: Boolean(token && submittedName),
    retry: false,
  })
  const charactersQuery = useQuery({
    queryKey: capabilityQueryKeys.characters(accountQuery.data?.id ?? 0),
    queryFn: ({ signal }) => capabilityApi.characters(token, accountQuery.data!.id, signal),
    enabled: Boolean(token && accountQuery.data),
    retry: false,
  })
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

  function submitSearch() {
    const normalized = search.trim()
    if (normalized) setSubmittedName(normalized)
  }

  return (
    <div className="grid gap-6">
      <PageHeader title={t("accounts.title")} description={t("accounts.description")} />

      {!token ? (
        <Alert>
          <KeyRound />
          <AlertTitle>{t("accounts.credentialTitle")}</AlertTitle>
          <AlertDescription className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <span>{t("accounts.credentialDescription")}</span>
            <Button asChild variant="outline" size="sm"><Link to="/api-keys">{t("accounts.connect")}</Link></Button>
          </AlertDescription>
        </Alert>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>{t("accounts.searchTitle")}</CardTitle>
            <CardDescription>{t("accounts.searchDescription")}</CardDescription>
          </CardHeader>
          <CardContent className="flex max-w-xl gap-2">
            <div className="relative flex-1">
              <Search className="absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                className="pl-8"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && submitSearch()}
                placeholder={t("accounts.searchPlaceholder")}
                aria-label={t("accounts.searchLabel")}
              />
            </div>
            <Button onClick={submitSearch} disabled={!search.trim()}>{t("accounts.search")}</Button>
          </CardContent>
        </Card>
      )}

      {accountQuery.error && <QueryError error={accountQuery.error} retry={() => void accountQuery.refetch()} />}

      {accountQuery.isPending && submittedName && token ? (
        <Skeleton className="h-40 w-full" />
      ) : accountQuery.data ? (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-3">
              <span className="flex size-9 items-center justify-center rounded-lg bg-muted"><UserRound className="size-4" /></span>
              <div>
                <CardTitle>{accountQuery.data.name}</CardTitle>
                <CardDescription>ID {accountQuery.data.id}</CardDescription>
              </div>
              <Badge className="ml-auto" variant={accountQuery.data.banned ? "destructive" : "secondary"}>
                {accountQuery.data.banned ? t("accounts.banned") : t("accounts.normal")}
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="grid gap-3 text-sm sm:grid-cols-3">
            <AccountField label={t("accounts.characterSlots")} value={formatNumber(accountQuery.data.characterslots)} />
            <AccountField label={t("accounts.gender")} value={String(accountQuery.data.gender)} />
            <AccountField label={t("accounts.characterCount")} value={charactersQuery.data ? formatNumber(charactersQuery.data.length) : "—"} />
          </CardContent>
        </Card>
      ) : null}

      {charactersQuery.error && <QueryError error={charactersQuery.error} retry={() => void charactersQuery.refetch()} />}

      {accountQuery.data && (
        <Card>
          <CardHeader>
            <CardTitle>{t("accounts.characters")}</CardTitle>
            <CardDescription>{t("accounts.charactersDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            {charactersQuery.isPending ? (
              <div className="grid gap-3">{[0, 1, 2].map((row) => <Skeleton key={row} className="h-10 w-full" />)}</div>
            ) : charactersQuery.data && charactersQuery.data.length > 0 ? (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("players.player")}</TableHead>
                    <TableHead className="text-right">{t("players.level")}</TableHead>
                    <TableHead className="text-right">{t("players.job")}</TableHead>
                    <TableHead className="text-right">{t("players.map")}</TableHead>
                    <TableHead className="text-right">{t("accounts.meso")}</TableHead>
                    <TableHead className="w-20 text-right">{t("common.operation")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {charactersQuery.data.map((character) => (
                    <TableRow key={character.id}>
                      <TableCell><div className="font-medium">{character.name}</div><div className="font-mono text-xs text-muted-foreground">ID {character.id}</div></TableCell>
                      <TableCell className="text-right tabular-nums">{character.level}</TableCell>
                      <TableCell className="text-right tabular-nums">{character.job}</TableCell>
                      <TableCell className="text-right tabular-nums">{character.map}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(character.meso)}</TableCell>
                      <TableCell className="text-right"><Button variant="ghost" size="sm" onClick={() => setKickTarget(character)}>{t("players.kick")}</Button></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <p className="py-10 text-center text-sm text-muted-foreground">{t("accounts.noCharacters")}</p>
            )}
          </CardContent>
        </Card>
      )}

      <ConfirmationDialog
        open={kickTarget !== null}
        onOpenChange={(open) => !open && setKickTarget(null)}
        title={t("players.kickTitle", { name: kickTarget?.name ?? t("players.thisPlayer") })}
        description={t("players.kickConfirmDescription", { id: kickTarget?.id ?? "—" })}
        confirmLabel={t("players.kickConfirm")}
        destructive
        pending={kickMutation.isPending}
        requireReason
        onConfirm={(reason) => kickTarget && kickMutation.mutate({ characterId: kickTarget.id, reason })}
      />
    </div>
  )
}

function AccountField({ label, value }: { label: string; value: string }) {
  return <div className="rounded-lg border p-3"><div className="text-xs text-muted-foreground">{label}</div><div className="mt-1 font-medium">{value}</div></div>
}
