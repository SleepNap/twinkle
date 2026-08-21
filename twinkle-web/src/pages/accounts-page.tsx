import { Ban, CalendarDays, ChevronDown, Copy, Eye, KeyRound, LogOut, Pencil, Plus, RefreshCw, Search, ShieldOff, Trash2, Volume2, VolumeX } from "lucide-react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useState } from "react"
import { enUS, zhCN } from "react-day-picker/locale"
import { toast } from "sonner"

import { adminApi, adminQueryKeys, type AdminAccount, type TemporaryPasswordResponse } from "@/api/admin"
import { ConfirmationDialog } from "@/components/confirmation-dialog"
import { PageHeader } from "@/components/page-header"
import { QueryError } from "@/components/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Calendar } from "@/components/ui/calendar"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
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
const BIRTHDAY_YEARS = Array.from(
  { length: new Date().getFullYear() - 1899 },
  (_, index) => String(new Date().getFullYear() - index),
)
type AccountStatus = "all" | "active" | "banned"
type ActionType = "ban" | "unban" | "mute" | "unmute" | "offline" | "temporaryPassword" | "delete"
interface PendingAction { type: ActionType; account: AdminAccount }
interface IssuedTemporaryPassword extends TemporaryPasswordResponse { accountName: string }

export function AccountsPage() {
  const { t, formatNumber, locale } = useI18n()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState("")
  const [submittedSearch, setSubmittedSearch] = useState("")
  const [status, setStatus] = useState<AccountStatus>("all")
  const [offset, setOffset] = useState(0)
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null)
  const [selectedCharacterId, setSelectedCharacterId] = useState<number | null>(null)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)
  const [issuedTemporaryPassword, setIssuedTemporaryPassword] = useState<IssuedTemporaryPassword | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [editTarget, setEditTarget] = useState<AdminAccount | null>(null)
  const [createName, setCreateName] = useState("")
  const [createPassword, setCreatePassword] = useState("")
  const [createPasswordConfirm, setCreatePasswordConfirm] = useState("")
  const [createReason, setCreateReason] = useState("")
  const [createAdvancedOpen, setCreateAdvancedOpen] = useState(false)
  const [createNick, setCreateNick] = useState("")
  const [createEmail, setCreateEmail] = useState("")
  const [createBirthday, setCreateBirthday] = useState("2005-05-11")
  const [createBirthdayOpen, setCreateBirthdayOpen] = useState(false)
  const [createBirthdayMonth, setCreateBirthdayMonth] = useState(() => new Date(2005, 4, 1))
  const [createPin, setCreatePin] = useState("")
  const [createPic, setCreatePic] = useState("")
  const [createCharacterSlots, setCreateCharacterSlots] = useState("3")
  const [createGender, setCreateGender] = useState("0")
  const [createLanguage, setCreateLanguage] = useState("3")
  const [createTosAccepted, setCreateTosAccepted] = useState(true)
  const [createNxCredit, setCreateNxCredit] = useState("0")
  const [createMaplePoint, setCreateMaplePoint] = useState("0")
  const [createNxPrepaid, setCreateNxPrepaid] = useState("0")
  const [createRewardPoints, setCreateRewardPoints] = useState("0")
  const [createVotePoints, setCreateVotePoints] = useState("0")

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
      if (type === "temporaryPassword") {
        return adminApi.generateTemporaryPassword(account.id, reason)
      }
      if (type === "delete") {
        return adminApi.deleteAccount(account.id, reason)
      }
      await adminApi.forceAccountOffline(account.id, reason)
    },
    onSuccess: (result, { action }) => {
      if (action.type === "temporaryPassword" && result && "temporaryPassword" in result) {
        setIssuedTemporaryPassword({ ...result, accountName: action.account.name })
      }
      if (action.type === "delete") {
        if (selectedAccountId === action.account.id) {
          setSelectedAccountId(null)
          setSelectedCharacterId(null)
        }
      }
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
  const createMutation = useMutation({
    mutationFn: () => {
      const profile = {
        nick: createNick.trim(),
        email: createEmail.trim(),
        birthday: createBirthday,
        characterSlots: Number(createCharacterSlots),
        gender: Number(createGender),
        language: Number(createLanguage),
        tosAccepted: createTosAccepted,
        nxCredit: Number(createNxCredit),
        maplePoint: Number(createMaplePoint),
        nxPrepaid: Number(createNxPrepaid),
        rewardPoints: Number(createRewardPoints),
        votePoints: Number(createVotePoints),
      }
      if (editTarget) {
        return adminApi.updateAccount(editTarget.id, {
          ...profile,
          ...(createPassword ? { password: createPassword } : {}),
          ...(createPin.trim() ? { pin: createPin.trim() } : {}),
          ...(createPic.trim() ? { pic: createPic.trim() } : {}),
        }, createReason.trim())
      }
      return adminApi.createAccount({
        ...profile,
        name: createName.trim(),
        password: createPassword,
        pin: createPin.trim(),
        pic: createPic.trim(),
      }, createReason.trim())
    },
    onSuccess: (account) => {
      toast.success(t(editTarget ? "accounts.updated" : "accounts.created"), { description: account.name })
      setCreateOpen(false)
      resetCreateForm()
      if (!editTarget) {
        setSearch(account.name)
        setSubmittedSearch(account.name)
        setStatus("all")
        setOffset(0)
      }
      void queryClient.invalidateQueries({ queryKey: ["admin", "accounts"] })
      void queryClient.invalidateQueries({ queryKey: adminQueryKeys.account(account.id) })
    },
    onError: (error) => toast.error(t(editTarget ? "accounts.updateFailed" : "accounts.createFailed"), { description: error.message }),
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

  function resetCreateForm() {
    setEditTarget(null)
    setCreateName("")
    setCreatePassword("")
    setCreatePasswordConfirm("")
    setCreateReason("")
    setCreateAdvancedOpen(false)
    setCreateNick("")
    setCreateEmail("")
    setCreateBirthday("2005-05-11")
    setCreateBirthdayOpen(false)
    setCreateBirthdayMonth(new Date(2005, 4, 1))
    setCreatePin("")
    setCreatePic("")
    setCreateCharacterSlots("3")
    setCreateGender("0")
    setCreateLanguage("3")
    setCreateTosAccepted(true)
    setCreateNxCredit("0")
    setCreateMaplePoint("0")
    setCreateNxPrepaid("0")
    setCreateRewardPoints("0")
    setCreateVotePoints("0")
  }

  function openCreateForm() {
    resetCreateForm()
    setCreateOpen(true)
  }

  function openEditForm(account: AdminAccount) {
    setEditTarget(account)
    setCreateName(account.name)
    setCreatePassword("")
    setCreatePasswordConfirm("")
    setCreateReason("")
    setCreateAdvancedOpen(true)
    setCreateNick(account.nick)
    setCreateEmail(account.email)
    setCreateBirthday(account.birthday || "2005-05-11")
    setCreateBirthdayOpen(false)
    setCreateBirthdayMonth(dateFromIso(account.birthday || "2005-05-11") ?? new Date(2005, 4, 1))
    setCreatePin("")
    setCreatePic("")
    setCreateCharacterSlots(String(account.characterSlots))
    setCreateGender(String(account.gender))
    setCreateLanguage(String(account.language))
    setCreateTosAccepted(account.tosAccepted)
    setCreateNxCredit(String(account.nxCredit))
    setCreateMaplePoint(String(account.maplePoint))
    setCreateNxPrepaid(String(account.nxPrepaid))
    setCreateRewardPoints(String(account.rewardPoints))
    setCreateVotePoints(String(account.votePoints))
    setCreateOpen(true)
  }

  const actionLabel = pendingAction ? t(`accounts.action.${pendingAction.type}`) : ""
  const total = accountsQuery.data?.total ?? 0
  const pageStart = total === 0 ? 0 : offset + 1
  const pageEnd = Math.min(offset + PAGE_SIZE, total)
  const createNameValid = /^[A-Za-z0-9_]{3,13}$/.test(createName.trim())
  const createPasswordValid = (editTarget !== null && createPassword === "")
    || (createPassword.length >= 6 && createPassword.length <= 12)
  const createPasswordMatches = createPassword === createPasswordConfirm
  const createEmailValid = createEmail.trim() === "" || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(createEmail.trim())
  const createPinValid = createPin === "" || /^\d{4}$/.test(createPin)
  const createPicValid = createPic === "" || /^\d{6}$/.test(createPic)
  const createSlotsValid = integerInRange(createCharacterSlots, 1, 15)
  const createLanguageValid = createLanguage === "2" || createLanguage === "3"
  const createPointsValid = [createNxCredit, createMaplePoint, createNxPrepaid, createRewardPoints, createVotePoints]
    .every((value) => integerInRange(value, 0, 2_147_483_647))
  const createValid = createNameValid && createPasswordValid && createPasswordMatches
    && createReason.trim() !== "" && createEmailValid && createPinValid && createPicValid && createSlotsValid
    && createLanguageValid && createPointsValid

  return (
    <div className="grid gap-6">
      <PageHeader
        title={t("accounts.title")}
        description={t("accounts.description")}
        action={<div className="flex gap-2">
          <Button size="sm" onClick={openCreateForm}>
            <Plus data-icon="inline-start" />
            {t("accounts.create")}
          </Button>
          <Button variant="outline" size="sm" onClick={() => void accountsQuery.refetch()} disabled={accountsQuery.isFetching}>
            <RefreshCw data-icon="inline-start" className={accountsQuery.isFetching ? "animate-spin" : undefined} />
            {t("common.refresh")}
          </Button>
        </div>}
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
          <Select
            value={status}
            onValueChange={(value) => changeStatus(value as AccountStatus)}
          >
            <SelectTrigger className="h-9 w-36" aria-label={t("accounts.statusFilter")}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t("accounts.status.all")}</SelectItem>
              <SelectItem value="active">{t("accounts.status.active")}</SelectItem>
              <SelectItem value="banned">{t("accounts.status.banned")}</SelectItem>
            </SelectContent>
          </Select>
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
                    <TableHead className="w-[34rem] text-right">{t("common.operation")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {accountsQuery.data.accounts.map((account) => (
                    <TableRow key={account.id} data-state={selectedAccountId === account.id ? "selected" : undefined}>
                      <TableCell>
                        <div className="font-medium">{account.name}</div>
                        {account.nick && <div className="text-xs text-muted-foreground">{account.nick}</div>}
                        <div className="font-mono text-xs text-muted-foreground">ID {account.id}</div>
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1">
                          <Badge variant={account.banned ? "destructive" : "secondary"}>
                            {account.banned ? t("accounts.banned") : t("accounts.normal")}
                          </Badge>
                          {account.muted && <Badge variant="outline">{t("accounts.muted")}</Badge>}
                          {account.temporaryPasswordActive && <Badge variant="outline">{t("accounts.temporaryPasswordActive")}</Badge>}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={account.loggedIn ? "default" : "outline"}>
                          {account.loggedIn ? t("accounts.online") : t("accounts.offline")}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-sm text-muted-foreground">{account.lastLogin || "—"}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex flex-wrap justify-end gap-1">
                          <Button variant="ghost" size="sm" onClick={() => selectAccount(account.id)}><Eye data-icon="inline-start" />{t("accounts.view")}</Button>
                          <Button variant="ghost" size="sm" onClick={() => openEditForm(account)}><Pencil data-icon="inline-start" />{t("accounts.edit")}</Button>
                          <Button variant="ghost" size="sm" onClick={() => setPendingAction({ type: account.banned ? "unban" : "ban", account })}>
                            {account.banned ? <ShieldOff data-icon="inline-start" /> : <Ban data-icon="inline-start" />}
                            {account.banned ? t("accounts.action.unban") : t("accounts.action.ban")}
                          </Button>
                          <Button variant="ghost" size="sm" onClick={() => setPendingAction({ type: account.muted ? "unmute" : "mute", account })}>
                            {account.muted ? <Volume2 data-icon="inline-start" /> : <VolumeX data-icon="inline-start" />}
                            {account.muted ? t("accounts.action.unmute") : t("accounts.action.mute")}
                          </Button>
                          <Button variant="ghost" size="sm" onClick={() => setPendingAction({ type: "offline", account })}><LogOut data-icon="inline-start" />{t("accounts.action.offline")}</Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={account.banned}
                            onClick={() => setPendingAction({ type: "temporaryPassword", account })}
                          ><KeyRound data-icon="inline-start" />{t("accounts.action.temporaryPassword")}</Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setPendingAction({ type: "delete", account })}
                          ><Trash2 data-icon="inline-start" />{t("accounts.action.delete")}</Button>
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

      <Dialog
        open={createOpen}
        onOpenChange={(open) => {
          if (createMutation.isPending) return
          setCreateOpen(open)
          if (!open) resetCreateForm()
        }}
      >
        <DialogContent showCloseButton={!createMutation.isPending} className="max-h-[90svh] overflow-y-auto sm:max-w-2xl">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (createValid) createMutation.mutate()
            }}
          >
            <DialogHeader>
              <DialogTitle>{t(editTarget ? "accounts.editTitle" : "accounts.createTitle")}</DialogTitle>
              <DialogDescription>{t(editTarget ? "accounts.editDescription" : "accounts.createDescription")}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-2">
              <Label htmlFor="create-account-name">{t("accounts.createName")}</Label>
              <Input
                id="create-account-name"
                value={createName}
                onChange={(event) => setCreateName(event.target.value)}
                disabled={editTarget !== null}
                minLength={3}
                maxLength={13}
                autoComplete="off"
                autoFocus
              />
              <p className="text-xs text-muted-foreground">{t("accounts.createNameHint")}</p>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="create-account-password">{t(editTarget ? "accounts.editPassword" : "accounts.createPassword")}</Label>
              <Input
                id="create-account-password"
                type="password"
                value={createPassword}
                onChange={(event) => setCreatePassword(event.target.value)}
                minLength={editTarget ? undefined : 6}
                maxLength={12}
                autoComplete="new-password"
              />
              <p className="text-xs text-muted-foreground">{t(editTarget ? "accounts.editPasswordHint" : "accounts.createPasswordHint")}</p>
            </div>
            <div className="grid gap-2">
              <Label htmlFor="create-account-password-confirm">{t("accounts.createPasswordConfirm")}</Label>
              <Input
                id="create-account-password-confirm"
                type="password"
                value={createPasswordConfirm}
                onChange={(event) => setCreatePasswordConfirm(event.target.value)}
                maxLength={12}
                autoComplete="new-password"
                aria-invalid={createPasswordConfirm !== "" && !createPasswordMatches}
              />
              {createPasswordConfirm !== "" && !createPasswordMatches && (
                <p className="text-xs text-destructive">{t("accounts.createPasswordMismatch")}</p>
              )}
            </div>
            <Button
              type="button"
              variant="outline"
              className="h-auto min-h-14 w-full justify-between px-4 py-3 text-left"
              aria-expanded={createAdvancedOpen}
              onClick={() => setCreateAdvancedOpen((open) => !open)}
            >
              <span className="grid min-w-0 gap-1 pr-4">
                <span className="leading-none">{t("accounts.moreSettings")}</span>
                <span className="whitespace-normal text-xs font-normal leading-relaxed text-muted-foreground">{t("accounts.moreSettingsDescription")}</span>
              </span>
              <ChevronDown className={createAdvancedOpen ? "rotate-180 transition-transform" : "transition-transform"} />
            </Button>
            {createAdvancedOpen && (
              <div className="grid gap-5 rounded-lg border bg-muted/20 p-4">
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="grid gap-2">
                    <Label htmlFor="create-account-nick">{t("accounts.nickname")}</Label>
                    <Input id="create-account-nick" value={createNick} maxLength={20} onChange={(event) => setCreateNick(event.target.value)} />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="create-account-email">{t("accounts.email")}</Label>
                    <Input
                      id="create-account-email"
                      type="email"
                      value={createEmail}
                      maxLength={45}
                      aria-invalid={!createEmailValid}
                      onChange={(event) => setCreateEmail(event.target.value)}
                    />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="create-account-birthday">{t("accounts.birthday")}</Label>
                    <Popover open={createBirthdayOpen} onOpenChange={setCreateBirthdayOpen}>
                      <PopoverTrigger asChild>
                        <Button id="create-account-birthday" type="button" variant="outline" className="w-full justify-start font-normal">
                          <CalendarDays data-icon="inline-start" />
                          {formatBirthday(createBirthday, locale)}
                        </Button>
                      </PopoverTrigger>
                      <PopoverContent align="start" className="w-auto p-0">
                        <div className="flex gap-2 border-b p-3">
                          <Select
                            value={String(createBirthdayMonth.getMonth())}
                            onValueChange={(value) => setCreateBirthdayMonth(new Date(
                              createBirthdayMonth.getFullYear(), Number(value), 1,
                            ))}
                          >
                            <SelectTrigger className="w-32"><SelectValue /></SelectTrigger>
                            <SelectContent>
                              {Array.from({ length: 12 }, (_, month) => (
                                <SelectItem key={month} value={String(month)}>
                                  {new Intl.DateTimeFormat(locale, { month: "long" }).format(new Date(2020, month, 1))}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                          <Select
                            value={String(createBirthdayMonth.getFullYear())}
                            onValueChange={(value) => setCreateBirthdayMonth(new Date(
                              Number(value), createBirthdayMonth.getMonth(), 1,
                            ))}
                          >
                            <SelectTrigger className="w-24"><SelectValue /></SelectTrigger>
                            <SelectContent className="max-h-64">
                              {BIRTHDAY_YEARS.map((year) => <SelectItem key={year} value={year}>{year}</SelectItem>)}
                            </SelectContent>
                          </Select>
                        </div>
                        <Calendar
                          mode="single"
                          selected={dateFromIso(createBirthday)}
                          month={createBirthdayMonth}
                          onMonthChange={setCreateBirthdayMonth}
                          hideNavigation
                          disabled={{ after: new Date() }}
                          locale={locale === "zh-CN" ? zhCN : enUS}
                          classNames={{ month_caption: "sr-only" }}
                          onSelect={(date) => {
                            if (!date) return
                            setCreateBirthday(dateToIso(date))
                            setCreateBirthdayMonth(new Date(date.getFullYear(), date.getMonth(), 1))
                            setCreateBirthdayOpen(false)
                          }}
                        />
                      </PopoverContent>
                    </Popover>
                  </div>
                  <div className="grid gap-2">
                    <Label>{t("accounts.genderSetting")}</Label>
                    <Select value={createGender} onValueChange={setCreateGender}>
                      <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="0">{t("accounts.genderMale")}</SelectItem>
                        <SelectItem value="1">{t("accounts.genderFemale")}</SelectItem>
                        <SelectItem value="10">{t("accounts.genderUnset")}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="create-account-slots">{t("accounts.characterSlots")}</Label>
                    <Input id="create-account-slots" type="number" min={1} max={15} value={createCharacterSlots} onChange={(event) => setCreateCharacterSlots(event.target.value)} />
                    <p className="text-xs text-muted-foreground">{t("accounts.characterSlotsHint")}</p>
                  </div>
                  <div className="grid gap-2">
                    <Label>{t("accounts.language")}</Label>
                    <Select value={createLanguage} onValueChange={setCreateLanguage}>
                      <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                      <SelectContent>
                        <SelectItem value="3">{t("accounts.languageChinese")}</SelectItem>
                        <SelectItem value="2">{t("accounts.languageEnglish")}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="create-account-pin">{t("accounts.pin")}</Label>
                    <Input id="create-account-pin" type="password" inputMode="numeric" value={createPin} maxLength={4} autoComplete="new-password" placeholder={editTarget ? t("accounts.secretKeepHint") : undefined} onChange={(event) => setCreatePin(event.target.value.replace(/\D/g, ""))} />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="create-account-pic">{t("accounts.pic")}</Label>
                    <Input id="create-account-pic" type="password" inputMode="numeric" value={createPic} maxLength={6} autoComplete="new-password" placeholder={editTarget ? t("accounts.secretKeepHint") : undefined} onChange={(event) => setCreatePic(event.target.value.replace(/\D/g, ""))} />
                  </div>
                </div>

                <label className="flex items-center gap-2 text-sm">
                  <Checkbox checked={createTosAccepted} onCheckedChange={(checked) => setCreateTosAccepted(checked === true)} />
                  {t("accounts.tosAccepted")}
                </label>

                <div className="grid gap-3">
                  <Label>{t("accounts.initialValues")}</Label>
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    <NumberField id="create-account-nx-credit" label={t("accounts.nxCredit")} value={createNxCredit} onChange={setCreateNxCredit} />
                    <NumberField id="create-account-maple-point" label={t("accounts.maplePoint")} value={createMaplePoint} onChange={setCreateMaplePoint} />
                    <NumberField id="create-account-nx-prepaid" label={t("accounts.nxPrepaid")} value={createNxPrepaid} onChange={setCreateNxPrepaid} />
                    <NumberField id="create-account-reward-points" label={t("accounts.rewardPoints")} value={createRewardPoints} onChange={setCreateRewardPoints} />
                    <NumberField id="create-account-vote-points" label={t("accounts.votePoints")} value={createVotePoints} onChange={setCreateVotePoints} />
                  </div>
                </div>
                <p className="text-xs leading-relaxed text-muted-foreground">{t("accounts.systemManagedFields")}</p>
              </div>
            )}
            <div className="grid gap-2">
              <Label htmlFor="create-account-reason">{t(editTarget ? "auth.reasonLabel" : "accounts.createReason")}</Label>
              <Input
                id="create-account-reason"
                value={createReason}
                onChange={(event) => setCreateReason(event.target.value)}
                maxLength={256}
                placeholder={t(editTarget ? "auth.reasonPlaceholder" : "accounts.createReasonPlaceholder")}
              />
            </div>
            <DialogFooter>
              {!createMutation.isPending && <DialogClose asChild><Button type="button" variant="outline">{t("common.cancel")}</Button></DialogClose>}
              <Button type="submit" disabled={createMutation.isPending || !createValid}>
                {t(editTarget ? "accounts.saveChanges" : "accounts.createConfirm")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmationDialog
        open={pendingAction !== null}
        onOpenChange={(open) => !open && setPendingAction(null)}
        title={t("accounts.actionTitle", { action: actionLabel, name: pendingAction?.account.name ?? "" })}
        description={pendingAction?.type === "delete" ? t("accounts.deleteDescription") : t("accounts.actionDescription")}
        confirmLabel={actionLabel}
        destructive={pendingAction?.type === "ban" || pendingAction?.type === "offline" || pendingAction?.type === "delete"}
        pending={actionMutation.isPending}
        requireReason
        confirmationText={pendingAction?.type === "delete" ? {
          label: t("accounts.deleteConfirmName", { name: pendingAction.account.name }),
          expected: pendingAction.account.name,
        } : undefined}
        onConfirm={(reason) => pendingAction && actionMutation.mutate({ action: pendingAction, reason })}
      />

      <Dialog
        open={issuedTemporaryPassword !== null}
        onOpenChange={(open) => !open && setIssuedTemporaryPassword(null)}
      >
        <DialogContent showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>{t("accounts.temporaryPasswordTitle")}</DialogTitle>
            <DialogDescription>
              {t("accounts.temporaryPasswordDescription", {
                name: issuedTemporaryPassword?.accountName ?? "",
                expiresAt: formatDate(issuedTemporaryPassword?.expiresAt ?? "", locale),
              })}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-2">
            <Input
              readOnly
              value={issuedTemporaryPassword?.temporaryPassword ?? ""}
              className="font-mono text-base tracking-wider"
              aria-label={t("accounts.temporaryPassword")}
              onFocus={(event) => event.currentTarget.select()}
            />
            <p className="text-xs text-muted-foreground">{t("accounts.temporaryPasswordWarning")}</p>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => {
                const password = issuedTemporaryPassword?.temporaryPassword
                if (!password) return
                void navigator.clipboard.writeText(password)
                  .then(() => toast.success(t("accounts.temporaryPasswordCopied")))
                  .catch(() => toast.error(t("accounts.temporaryPasswordCopyFailed")))
              }}
            >
              <Copy data-icon="inline-start" />
              {t("accounts.copyTemporaryPassword")}
            </Button>
            <DialogClose asChild>
              <Button onClick={() => setIssuedTemporaryPassword(null)}>{t("accounts.temporaryPasswordSaved")}</Button>
            </DialogClose>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function formatDate(value: string, locale: string) {
  if (!value) return "—"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(date)
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

function dateFromIso(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return undefined
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
  return date.getFullYear() === Number(match[1])
    && date.getMonth() === Number(match[2]) - 1
    && date.getDate() === Number(match[3]) ? date : undefined
}

function dateToIso(date: Date) {
  const year = String(date.getFullYear()).padStart(4, "0")
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function formatBirthday(value: string, locale: string) {
  const date = dateFromIso(value)
  return date ? new Intl.DateTimeFormat(locale, { dateStyle: "long" }).format(date) : "—"
}

function integerInRange(value: string, min: number, max: number) {
  if (value.trim() === "") return false
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed >= min && parsed <= max
}

function NumberField({ id, label, value, onChange }: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">{label}</Label>
      <Input
        id={id}
        type="number"
        min={0}
        max={2_147_483_647}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  )
}
