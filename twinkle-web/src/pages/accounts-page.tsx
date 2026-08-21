import { Ban, Eye, LogOut, RefreshCw, Search, ShieldOff, Volume2, VolumeX } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { toast } from "sonner"

import { adminApi, adminQueryKeys, type AdminAccount } from "@/api/admin"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useI18n } from "@/i18n"

const PAGE_SIZE = 20
type AccountStatus = "all" | "active" | "banned"
type ActionType = "ban" | "unban" | "mute" | "unmute" | "offline"
interface PendingAction { type: ActionType; account: AdminAccount }

export function AccountsPage() {
  const { t, formatNumber } = useI18n()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [submittedSearch, setSubmittedSearch] = useState("")
  const [status, setStatus] = useState<AccountStatus>("all")
  const [offset, setOffset] = useState(0)
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null)
  const [selectedCharacterId, setSelectedCharacterId] = useState<number | null>(null)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)

  const accountsQuery = useQuery({
    queryKey: adminQueryKeys.accounts(submittedSearch, status, offset),
    queryFn: ({ signal }) => adminApi.accounts(submittedSearch, status, offset, PAGE_SIZE, signal),
  })
  const detailQuery = useQuery({
    queryKey: adminQueryKeys.account(selectedAccountId ?? 0),
    queryFn: ({ signal }) => adminApi.account(selectedAccountId!, signal),
    enabled: selectedAccountId !== null,
  })
  const characterQuery = useQuery({
    queryKey: adminQueryKeys.character(selectedAccountId ?? 0, selectedCharacterId ?? 0),
    queryFn: ({ signal }) => adminApi.character(selectedAccountId!, selectedCharacterId!, signal),
    enabled: selectedAccountId !== null && selectedCharacterId !== null,
  })

  const actionMutation = useMutation({
    mutationFn: async ({ action, reason }: { action: PendingAction; reason: string }) => {
      const { account, type } = action
      if (type === "ban") {
        await adminApi.updateAccountRestrictions(account.id, { banned: true, banReason: reason }, reason)
        return
      }
      if (type === "unban") {
        await adminApi.updateAccountRestrictions(account.id, { banned: false }, reason)
        return
      }
      if (type === "mute") {
        await adminApi.updateAccountRestrictions(account.id, { muted: true }, reason)
        return
      }
      if (type === "unmute") {
        await adminApi.updateAccountRestrictions(account.id, { muted: false }, reason)
        return
      }
      await adminApi.forceAccountOffline(account.id, reason)
    },
    onSuccess: (_result, { action }) => {
      toast.success(t("accounts.actionSucceeded"), {
        description: t("accounts.actionSucceededDescription", { name: action.account.name }),
      })
      setPendingAction(null)
      void queryClient.invalidateQueries({ queryKey: ["admin", "accounts"] })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.account(action.account.id) })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.online })
    },
    onError: (error) => toast.error(t("accounts.actionFailed"), { description: error.message }),
  })

  function submitSearch() {
    setOffset(0)
    setSubmittedSearch(search.trim())
  }

  function changeStatus(next: AccountStatus) {
    setStatus(next)
    setOffset(0)
  }

  function selectAccount(accountId: number) {
    setSelectedAccountId(accountId)
    setSelectedCharacterId(null)
  }

  const actionLabel = pendingAction ? t(`accounts.action.${pendingAction.type}`) : ""
  const total = accountsQuery.data?.total ?? 0
  const pageStart = total === 0 ? 0 : offset + 1
  const pageEnd = Math.min(offset + PAGE_SIZE, total)

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("accounts.title")}
        description={t("accounts.description")}
        action={
          <Button variant="outline" size="sm" onClick={() => void accountsQuery.refetch()} disabled={accountsQuery.isFetching}>
            <RefreshCw data-icon="inline-start" className={accountsQuery.isFetching ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        }
      />

      <Card>
        <CardHeader>
          <CardTitle>{t("accounts.searchTitle")}</CardTitle>
          <CardDescription>{t("accounts.searchDescription")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <div className="relative max-w-xl flex-1">
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
          <select
            value={status}
            onChange={(event) => changeStatus(event.target.value as AccountStatus)}
            className="h-9 rounded-md border bg-background px-3 text-sm"
            aria-label={t("accounts.statusFilter")}
          >
            <option value="all">{t("accounts.status.all")}</option>
            <option value="active">{t("accounts.status.active")}</option>
            <option value="banned">{t("accounts.status.banned")}</option>
          </select>
          <Button onClick={submitSearch}>{t("accounts.search")}</Button>
        </CardContent>
      </Card>

      {accountsQuery.error && <QueryError error={accountsQuery.error} retry={() => void accountsQuery.refetch()} />}

      <Card>
        <CardHeader className="flex-row items-center justify-between gap-4">
          <div>
            <CardTitle>{t("accounts.list")}</CardTitle>
            <CardDescription>{t("accounts.listDescription", { total: formatNumber(total) })}</CardDescription>
          </div>
          <span className="text-xs text-muted-foreground">{pageStart}–{pageEnd} / {formatNumber(total)}</span>
        </CardHeader>
        <CardContent>
          {accountsQuery.isPending ? (
            <div className="grid gap-3">{[0, 1, 2, 3].map((row) => <Skeleton key={row} className="h-11 w-full" />)}</div>
          ) : accountsQuery.data && accountsQuery.data.accounts.length > 0 ? (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("accounts.account")}</TableHead>
                    <TableHead>{t("accounts.restrictions")}</TableHead>
                    <TableHead>{t("accounts.loginState")}</TableHead>
                    <TableHead>{t("accounts.lastLogin")}</TableHead>
                    <TableHead className="w-52 text-right">{t("common.operation")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {accountsQuery.data.accounts.map((account) => (
                    <TableRow key={account.id} data-state={selectedAccountId === account.id ? "selected" : undefined}>
                      <TableCell>
                        <div className="font-medium">{account.name}</div>
                        <div className="font-mono text-xs text-muted-foreground">ID {account.id}</div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          <Badge variant={account.banned ? "destructive" : "secondary"}>
                            {account.banned ? t("accounts.banned") : t("accounts.normal")}
                          </Badge>
                          {account.muted && <Badge variant="outline">{t("accounts.muted")}</Badge>}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={account.loggedIn ? "default" : "outline"}>
                          {account.loggedIn ? t("accounts.online") : t("accounts.offline")}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-sm text-muted-foreground">{account.lastLogin || "—"}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1">
                          <Button variant="ghost" size="icon-sm" aria-label={t("accounts.view")} onClick={() => selectAccount(account.id)}><Eye /></Button>
                          <Button variant="ghost" size="icon-sm" aria-label={account.banned ? t("accounts.action.unban") : t("accounts.action.ban")} onClick={() => setPendingAction({ type: account.banned ? "unban" : "ban", account })}>
                            {account.banned ? <ShieldOff /> : <Ban />}
                          </Button>
                          <Button variant="ghost" size="icon-sm" aria-label={account.muted ? t("accounts.action.unmute") : t("accounts.action.mute")} onClick={() => setPendingAction({ type: account.muted ? "unmute" : "mute", account })}>
                            {account.muted ? <Volume2 /> : <VolumeX />}
                          </Button>
                          <Button variant="ghost" size="icon-sm" aria-label={t("accounts.action.offline")} onClick={() => setPendingAction({ type: "offline", account })}><LogOut /></Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <div className="mt-4 flex justify-end gap-2">
                <Button variant="outline" size="sm" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>{t("accounts.previous")}</Button>
                <Button variant="outline" size="sm" disabled={offset + PAGE_SIZE >= total} onClick={() => setOffset(offset + PAGE_SIZE)}>{t("accounts.next")}</Button>
              </div>
            </>
          ) : (
            <p className="py-12 text-center text-sm text-muted-foreground">{t("accounts.empty")}</p>
          )}
        </CardContent>
      </Card>

      {selectedAccountId !== null && (
        <Card>
          <CardHeader>
            <CardTitle>{detailQuery.data?.account.name ?? t("accounts.detail")}</CardTitle>
            <CardDescription>{t("accounts.charactersDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            {detailQuery.isPending ? (
              <Skeleton className="h-36 w-full" />
            ) : detailQuery.error ? (
              <QueryError error={detailQuery.error} retry={() => void detailQuery.refetch()} />
            ) : detailQuery.data && detailQuery.data.characters.length > 0 ? (
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{t("players.player")}</TableHead>
                  <TableHead>{t("accounts.world")}</TableHead>
                  <TableHead>{t("players.level")}</TableHead>
                  <TableHead>{t("players.job")}</TableHead>
                  <TableHead>{t("players.map")}</TableHead>
                  <TableHead className="text-right">{t("accounts.meso")}</TableHead>
                  <TableHead className="w-24 text-right">{t("common.operation")}</TableHead>
                </TableRow></TableHeader>
                <TableBody>
                  {detailQuery.data.characters.map((character) => (
                    <TableRow key={character.id}>
                      <TableCell><div className="font-medium">{character.name}</div><div className="font-mono text-xs text-muted-foreground">ID {character.id}</div></TableCell>
                      <TableCell>{character.world}</TableCell>
                      <TableCell>{character.level}</TableCell>
                      <TableCell>{character.job}</TableCell>
                      <TableCell>{character.map}</TableCell>
                      <TableCell className="text-right tabular-nums">{formatNumber(character.meso)}</TableCell>
                      <TableCell className="text-right">
                        <Button variant="outline" size="sm" onClick={() => setSelectedCharacterId(character.id)}>
                          <Eye data-icon="inline-start" />
                          {t("accounts.characterView")}
                        </Button>
                      </TableCell>
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

      {selectedAccountId !== null && selectedCharacterId !== null && (
        <Card>
          <CardHeader>
            <CardTitle>{characterQuery.data?.character.name ?? t("accounts.characterDetail")}</CardTitle>
            <CardDescription>{t("accounts.characterDetailDescription")}</CardDescription>
          </CardHeader>
          <CardContent>
            {characterQuery.isPending ? (
              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                {[0, 1, 2, 3].map((item) => <Skeleton key={item} className="h-20 w-full" />)}
              </div>
            ) : characterQuery.error ? (
              <QueryError error={characterQuery.error} retry={() => void characterQuery.refetch()} />
            ) : characterQuery.data ? (
              <Tabs defaultValue="overview">
                <TabsList className="max-w-full overflow-x-auto" variant="line">
                  <TabsTrigger value="overview">{t("accounts.tab.overview")}</TabsTrigger>
                  <TabsTrigger value="inventory">{t("accounts.tab.inventory")} ({characterQuery.data.inventory.length})</TabsTrigger>
                  <TabsTrigger value="quests">{t("accounts.tab.quests")} ({characterQuery.data.quests.length})</TabsTrigger>
                  <TabsTrigger value="skills">{t("accounts.tab.skills")} ({characterQuery.data.skills.length})</TabsTrigger>
                  <TabsTrigger value="buddies">{t("accounts.tab.buddies")} ({characterQuery.data.buddies.length})</TabsTrigger>
                </TabsList>

                <TabsContent value="overview" className="pt-4">
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                    <Snapshot label={t("accounts.levelAndJob")} value={`${characterQuery.data.character.level} / ${characterQuery.data.character.job}`} />
                    <Snapshot label="HP / MP" value={`${formatNumber(characterQuery.data.character.hp)} / ${formatNumber(characterQuery.data.character.mp)}`} hint={`${formatNumber(characterQuery.data.character.maxHp)} / ${formatNumber(characterQuery.data.character.maxMp)}`} />
                    <Snapshot label="STR / DEX / INT / LUK" value={`${characterQuery.data.character.strStat} / ${characterQuery.data.character.dexStat} / ${characterQuery.data.character.intStat} / ${characterQuery.data.character.lukStat}`} />
                    <Snapshot label={t("accounts.mapAndSpawn")} value={`${characterQuery.data.character.map} / ${characterQuery.data.character.spawnPoint}`} />
                    <Snapshot label={t("accounts.meso")} value={formatNumber(characterQuery.data.currencies.meso)} />
                    <Snapshot label="NX Credit / Prepaid" value={`${formatNumber(characterQuery.data.currencies.nxCredit)} / ${formatNumber(characterQuery.data.currencies.nxPrepaid)}`} />
                    <Snapshot label="Maple Point / Reward" value={`${formatNumber(characterQuery.data.currencies.maplePoint)} / ${formatNumber(characterQuery.data.currencies.rewardPoints)}`} />
                    <Snapshot label={t("accounts.guildAndParty")} value={`${characterQuery.data.character.guildId || "—"} / ${characterQuery.data.character.partyId || "—"}`} />
                    <Snapshot label="EXP" value={formatNumber(characterQuery.data.character.exp)} />
                    <Snapshot label="AP / SP" value={`${characterQuery.data.character.ap} / ${characterQuery.data.character.sp || "—"}`} />
                    <Snapshot label={t("accounts.fameAndGm")} value={`${characterQuery.data.character.fame} / ${characterQuery.data.character.gm}`} />
                    <Snapshot label={t("accounts.lastLogout")} value={characterQuery.data.character.lastLogoutTime || "—"} />
                  </div>
                </TabsContent>

                <TabsContent value="inventory" className="overflow-x-auto pt-4">
                  {characterQuery.data.inventory.length > 0 ? (
                    <Table>
                      <TableHeader><TableRow>
                        <TableHead>{t("accounts.itemId")}</TableHead><TableHead>{t("accounts.inventorySlot")}</TableHead>
                        <TableHead className="text-right">{t("accounts.quantity")}</TableHead><TableHead>{t("accounts.owner")}</TableHead>
                        <TableHead>{t("accounts.equipmentStats")}</TableHead><TableHead>{t("accounts.expiration")}</TableHead>
                      </TableRow></TableHeader>
                      <TableBody>{characterQuery.data.inventory.map((item, index) => (
                        <TableRow key={item.id ?? `${item.itemId}-${item.inventoryType}-${item.position}-${index}`}>
                          <TableCell className="font-mono">{item.itemId}</TableCell>
                          <TableCell>{item.inventoryType} / {item.position}</TableCell>
                          <TableCell className="text-right tabular-nums">{formatNumber(item.quantity)}</TableCell>
                          <TableCell>{item.owner || "—"}</TableCell>
                          <TableCell className="whitespace-nowrap text-xs text-muted-foreground">{equipmentStats(item)}</TableCell>
                          <TableCell className="font-mono text-xs">{item.expiration === -1 ? t("accounts.neverExpires") : item.expiration}</TableCell>
                        </TableRow>
                      ))}</TableBody>
                    </Table>
                  ) : <EmptyState text={t("accounts.noInventory")} />}
                </TabsContent>

                <TabsContent value="quests" className="overflow-x-auto pt-4">
                  {characterQuery.data.quests.length > 0 ? (
                    <Table>
                      <TableHeader><TableRow><TableHead>{t("accounts.questId")}</TableHead><TableHead>{t("accounts.questStatus")}</TableHead><TableHead>{t("accounts.questProgress")}</TableHead><TableHead>{t("accounts.completed")}</TableHead></TableRow></TableHeader>
                      <TableBody>{characterQuery.data.quests.map((quest) => (
                        <TableRow key={quest.questId}>
                          <TableCell className="font-mono">{quest.questId}</TableCell><TableCell>{quest.status}</TableCell>
                          <TableCell className="font-mono text-xs">{quest.progress.length > 0 ? quest.progress.map((entry) => `${entry.progressId}: ${entry.value}`).join(", ") : "—"}</TableCell>
                          <TableCell>{quest.completed || "—"}</TableCell>
                        </TableRow>
                      ))}</TableBody>
                    </Table>
                  ) : <EmptyState text={t("accounts.noQuests")} />}
                </TabsContent>

                <TabsContent value="skills" className="overflow-x-auto pt-4">
                  {characterQuery.data.skills.length > 0 ? (
                    <Table>
                      <TableHeader><TableRow><TableHead>{t("accounts.skillId")}</TableHead><TableHead>{t("accounts.skillLevel")}</TableHead><TableHead>{t("accounts.masterLevel")}</TableHead><TableHead>{t("accounts.expiration")}</TableHead></TableRow></TableHeader>
                      <TableBody>{characterQuery.data.skills.map((skill) => (
                        <TableRow key={skill.skillId}><TableCell className="font-mono">{skill.skillId}</TableCell><TableCell>{skill.level}</TableCell><TableCell>{skill.masterLevel}</TableCell><TableCell className="font-mono text-xs">{skill.expiration === -1 ? t("accounts.neverExpires") : skill.expiration}</TableCell></TableRow>
                      ))}</TableBody>
                    </Table>
                  ) : <EmptyState text={t("accounts.noSkills")} />}
                </TabsContent>

                <TabsContent value="buddies" className="overflow-x-auto pt-4">
                  {characterQuery.data.buddies.length > 0 ? (
                    <Table>
                      <TableHeader><TableRow><TableHead>{t("accounts.buddy")}</TableHead><TableHead>{t("accounts.buddyStatus")}</TableHead><TableHead>{t("accounts.createdAt")}</TableHead></TableRow></TableHeader>
                      <TableBody>{characterQuery.data.buddies.map((buddy) => (
                        <TableRow key={buddy.characterId}><TableCell><div className="font-medium">{buddy.name || "—"}</div><div className="font-mono text-xs text-muted-foreground">ID {buddy.characterId}</div></TableCell><TableCell><Badge variant="outline">{buddy.status}</Badge></TableCell><TableCell className="text-muted-foreground">{buddy.createdAt || "—"}</TableCell></TableRow>
                      ))}</TableBody>
                    </Table>
                  ) : <EmptyState text={t("accounts.noBuddies")} />}
                </TabsContent>
              </Tabs>
            ) : null}
          </CardContent>
        </Card>
      )}

      <ConfirmationDialog
        open={pendingAction !== null}
        onOpenChange={(open) => !open && setPendingAction(null)}
        title={t("accounts.actionTitle", { action: actionLabel, name: pendingAction?.account.name ?? "" })}
        description={t("accounts.actionDescription")}
        confirmLabel={actionLabel}
        destructive={pendingAction?.type === "ban" || pendingAction?.type === "offline"}
        pending={actionMutation.isPending}
        requireReason
        onConfirm={(reason) => pendingAction && actionMutation.mutate({ action: pendingAction, reason })}
      />
    </div>
  )
}

function Snapshot({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 font-medium tabular-nums">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-muted-foreground">{hint}</div>}
    </div>
  )
}

function EmptyState({ text }: { text: string }) {
  return <p className="py-10 text-center text-sm text-muted-foreground">{text}</p>
}

function equipmentStats(item: { strStat: number; dexStat: number; intStat: number; lukStat: number; wAtk: number; mAtk: number }) {
  const stats = [
    ["STR", item.strStat], ["DEX", item.dexStat], ["INT", item.intStat], ["LUK", item.lukStat],
    ["WATK", item.wAtk], ["MATK", item.mAtk],
  ].filter(([, value]) => value !== 0)
  return stats.length > 0 ? stats.map(([name, value]) => `${name} ${value}`).join(" · ") : "—"
}
