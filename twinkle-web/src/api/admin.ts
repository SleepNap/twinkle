import { translate } from "@/i18n"

const ADMIN_API_BASE = "/admin/v1"

export interface HealthResponse {
  healthy: boolean
  checks: Record<string, unknown>
}

export interface Channel {
  channelId: number
  host: string
  port: number
  onlineCount: number
}

export interface ChannelsResponse {
  channels: Channel[]
}

export interface OnlinePlayer {
  characterId: number
  name: string
  mapId: number
  level: number
  job: number
}

export interface OnlineResponse {
  onlineCount: number
  players: OnlinePlayer[]
}

export interface ConfigResponse {
  version: number
  configs: Record<string, string>
}

export interface ConfigSetResponse {
  key: string
  value: string
  version: number
}

export interface InFlightResponse {
  inFlightCount: number
  entities: number[]
}

export interface RestartPhaseResponse {
  phase: "RUNNING" | "DRAINING" | "FLUSH_DIRTY" | "RESTARTING" | "RESTORED" | "FAILED"
}

export interface KickResponse {
  kicked: true
  characterId: number
}

export type PacketTraceDirection = "INBOUND" | "OUTBOUND"
export type PacketTraceFilterMode = "INCLUDE" | "EXCLUDE"

export interface PacketTraceOpcode {
  direction: PacketTraceDirection
  value: number
  name: string
  sensitive: boolean
  defaultExcluded: boolean
}

export interface PacketTraceCatalog {
  opcodes: PacketTraceOpcode[]
  defaultExcluded: string[]
  neverCaptured: string[]
}

export interface PacketTraceConfig {
  mode: PacketTraceFilterMode
  directions: PacketTraceDirection[]
  opcodeNames: string[]
  maxPayloadBytes: number
}

export interface PacketTraceEvent {
  sequence: number
  timestampEpochMillis: number
  direction: PacketTraceDirection
  opcode: number
  opcodeName: string
  packetLength: number
  capturedLength: number
  truncated: boolean
  payloadHex: string
}

export interface PacketTraceSnapshot {
  configured: boolean
  enabled: boolean
  config: PacketTraceConfig | null
  lastSequence: number
  droppedEvents: number
  events: PacketTraceEvent[]
}

export interface ScriptReloadResponse {
  changed: number
}

export interface LogicReloadResponse {
  safeSwitched: number
  interrupted: number
  newVersion: number
}

export interface WzReloadResponse {
  version: number
  resources: Record<string, number>
  runtimeObjects: Record<string, number>
}

export interface RestartResponse extends RestartPhaseResponse {
  accepted: true
}

export interface AdminRole {
  id: number
  roleCode: string
  displayName: string
  description: string
  permissions: string
}

export interface RolesResponse {
  roles: AdminRole[]
}

export interface AccountOption {
  id: number
  name: string
}

export interface AdminAccount extends AccountOption {
  banned: boolean
  banReason: string
  muted: boolean
  loggedIn: boolean
  lastLogin: string
  createdAt: string
  tempBan: string
  characterSlots: number
  gender: number
  nick: string
  email: string
  birthday: string
  language: number
  tosAccepted: boolean
  nxCredit: number
  maplePoint: number
  nxPrepaid: number
  rewardPoints: number
  votePoints: number
  pinConfigured: boolean
  picConfigured: boolean
  temporaryPasswordActive: boolean
  temporaryPasswordExpiresAt: string
}

export interface CreateAccountInput {
  name: string
  password: string
  nick?: string
  email?: string
  birthday?: string
  pin?: string
  pic?: string
  characterSlots?: number
  gender?: number
  language?: number
  tosAccepted?: boolean
  nxCredit?: number
  maplePoint?: number
  nxPrepaid?: number
  rewardPoints?: number
  votePoints?: number
}

export type UpdateAccountInput = Omit<CreateAccountInput, "name" | "password"> & {
  password?: string
}

export interface DeleteAccountResponse {
  deleted: true
  accountId: number
  characters: number
  relatedRows: number
}

export interface TemporaryPasswordResponse {
  generated: true
  accountId: number
  temporaryPassword: string
  expiresAt: string
  oneTime: true
}

export interface AdminCharacter {
  id: number
  name: string
  world: number
  level: number
  job: number
  map: number
  meso: number
  fame: number
  guildId: number
  lastLogoutTime: string
  online: boolean
}

export interface AccountsPageResponse {
  total: number
  offset: number
  limit: number
  accounts: AdminAccount[]
}

export interface AccountDetailResponse {
  account: AdminAccount
  characters: AdminCharacter[]
}

export interface AdminCharacterProfile {
  id: number
  accountId: number
  name: string
  world: number
  level: number
  exp: number
  job: number
  map: number
  spawnPoint: number
  hp: number
  maxHp: number
  mp: number
  maxMp: number
  strStat: number
  dexStat: number
  intStat: number
  lukStat: number
  ap: number
  sp: string
  fame: number
  gm: number
  partyId: number
  guildId: number
  guildRank: number
  buddyCapacity: number
  createdAt: string
  lastLogoutTime: string
  lastExpGainTime: string
}

export interface AdminCharacterCurrencies {
  meso: number
  nxCredit: number
  maplePoint: number
  nxPrepaid: number
  rewardPoints: number
  votePoints: number
}

export interface AdminInventoryItem {
  id: number | null
  itemId: number
  type: number
  inventoryType: number
  position: number
  quantity: number
  owner: string
  flag: number
  expiration: number
  cashId: number
  petId: number
  upgradeSlots: number
  itemLevel: number
  itemExp: number
  strStat: number
  dexStat: number
  intStat: number
  lukStat: number
  wAtk: number
  mAtk: number
  wDef: number
  mDef: number
  petName: string
  petLevel: number
}

export interface AdminQuestProgress {
  progressId: number
  value: string
}

export interface AdminQuest {
  questId: number
  status: number
  time: number
  expires: number
  forfeited: number
  completed: number
  info: number
  progress: AdminQuestProgress[]
}

export interface AdminSkill {
  skillId: number
  level: number
  masterLevel: number
  expiration: number
}

export interface AdminBuddy {
  characterId: number
  name: string
  status: string
  createdAt: string
}

export interface CharacterAdminDetailResponse {
  character: AdminCharacterProfile
  currencies: AdminCharacterCurrencies
  inventory: AdminInventoryItem[]
  quests: AdminQuest[]
  skills: AdminSkill[]
  buddies: AdminBuddy[]
}

export interface AccountRolesResponse {
  accountId: number
  roles: AdminRole[]
}

export interface ApiRequestAudit {
  id: number
  requestId: string
  apiKeyId: number | null
  keyPrefix: string
  method: string
  path: string
  requiredScope: string
  outcome: string
  statusCode: number
  remoteAddress: string
  elapsedMs: number
  createdAt: string
}

export interface ToolExecutionAudit {
  id: number
  auditRef: string
  executionId: string
  requestId: string
  taskId: string | null
  stepId: string | null
  subjectId: string
  credentialId: string
  source: string
  serverId: string
  toolId: string
  toolVersion: string
  requiredScopes: string
  authorizationResult: string
  policyVersion: string
  parameterSummary: string
  resultStatus: string
  errorCode: string | null
  intentSummary: string | null
  startedAt: string
  completedAt: string
}

export interface AuditPage<T> {
  total: number
  limit: number
  records: T[]
}

export interface BackgroundTaskRun {
  taskId: string
  taskType: string
  displayName: string
  source: string
  scheduleId: string
  status: "running" | "succeeded" | "failed" | "cancelled"
  trigger: "schedule" | "manual" | "retry" | string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
  attempt: number
  maxAttempts: number
  cancellable: boolean
  retryable: boolean
  requestId: string | null
  subjectId: string | null
  errorCode: string | null
  errorSummary: string | null
  queueDelayMs: number | null
  executorType: "virtual-thread-per-task" | string
  threadName: string | null
}

export interface ThreadExecutorMetrics {
  executorType: string
  virtualThreads: boolean
  closed: boolean
  submittedTasks: number
  runningTasks: number
  succeededTasks: number
  failedTasks: number
  rejectedTasks: number
}

export interface BackgroundTaskMetrics {
  registeredSchedules: number
  retainedRuns: number
  runningRuns: number
  succeededRuns: number
  failedRuns: number
  cancelledRuns: number
  totalRuns: number
  totalErrors: number
  executor: ThreadExecutorMetrics
}

export interface TaskSchedule {
  scheduleId: string
  taskType: string
  displayName: string
  source: string
  schedule: string
  enabled: boolean
  retryable: boolean
  nextRunAt: string | null
  lastRunAt: string | null
  lastStatus: BackgroundTaskRun["status"] | null
  runCount: number
  errorCount: number
}

export interface TasksResponse {
  limit: number
  tasks: BackgroundTaskRun[]
  metrics: BackgroundTaskMetrics
}

export interface SchedulesResponse {
  schedules: TaskSchedule[]
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message)
    this.name = "ApiError"
  }
}

const ADMIN_SESSION_KEY = "twinkle.console.admin-session"
const ADMIN_IDENTITY_KEY = "twinkle.console.admin-identity"

function adminToken(): string {
  if (typeof window === "undefined") return ""
  return window.sessionStorage.getItem(ADMIN_SESSION_KEY) ?? ""
}

function redirectToLogin(): void {
  window.sessionStorage.removeItem(ADMIN_SESSION_KEY)
  window.sessionStorage.removeItem(ADMIN_IDENTITY_KEY)
  if (window.location.pathname !== "/login") {
    window.location.assign("/login")
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response

  try {
    response = await fetch(`${ADMIN_API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(adminToken() ? { Authorization: `Bearer ${adminToken()}` } : {}),
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw error
    }
    throw new ApiError(translate("api.unreachable"))
  }

  if (response.status === 401) {
    redirectToLogin()
    throw new ApiError(translate("api.unauthenticated"), 401)
  }

  if (!response.ok) {
    const body = contentType(response).includes("application/json")
      ? await response.json().catch(() => null) as { error?: string; message?: string } | null
      : null
    throw new ApiError(
      body?.message ?? humanizeApiError(body?.error) ?? translate("api.httpError", {
        status: response.status,
        statusText: response.statusText,
      }).trim(),
      response.status,
    )
  }

  if (!contentType(response).includes("application/json")) {
    throw new ApiError(translate("api.invalidJson"), response.status)
  }

  return response.json() as Promise<T>
}

function humanizeApiError(error?: string) {
  if (!error) return undefined
  const messages: Record<string, string> = {
    config_not_found: translate("api.configNotFound"),
    character_not_online: translate("api.characterNotOnline"),
    account_not_found: translate("api.accountNotFound"),
    account_name_exists: translate("api.accountNameExists"),
    invalid_account_name: translate("api.invalidAccountName"),
    invalid_account_password: translate("api.invalidAccountPassword"),
    invalid_account_profile: translate("api.invalidAccountProfile"),
    account_still_online: translate("api.accountStillOnline"),
    character_not_found: translate("api.characterNotFound"),
    invalid_packet_trace_filter: translate("api.invalidPacketTraceFilter"),
  }
  return messages[error] ?? error
}

function contentType(response: Response) {
  return response.headers.get("content-type") ?? ""
}

export const adminApi = {
  health: (signal?: AbortSignal) => request<HealthResponse>("/health", { signal }),
  channels: (signal?: AbortSignal) => request<ChannelsResponse>("/channels", { signal }),
  online: (signal?: AbortSignal) => request<OnlineResponse>("/online", { signal }),
  config: (signal?: AbortSignal) => request<ConfigResponse>("/config", { signal }),
  setConfig: (key: string, value: string, reason: string) =>
    request<ConfigSetResponse>("/config", {
      method: "POST",
      body: JSON.stringify({ key, value }),
      headers: { "X-Admin-Reason": reason },
    }),
  inFlight: async (signal?: AbortSignal) => {
    const response = await request<Partial<InFlightResponse>>("/reload/in-flight", { signal })
    return {
      inFlightCount: response.inFlightCount ?? 0,
      entities: Array.isArray(response.entities) ? response.entities : [],
    }
  },
  restartPhase: (signal?: AbortSignal) =>
    request<RestartPhaseResponse>("/restart/phase", { signal }),
  kick: (characterId: number, reason: string) =>
    request<KickResponse>("/kick", {
      method: "POST",
      body: JSON.stringify({ characterId }),
      headers: { "X-Admin-Reason": reason },
    }),
  reloadScripts: (reason: string) =>
    request<ScriptReloadResponse>("/reload/scripts", {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  reloadLogic: (reason: string) =>
    request<LogicReloadResponse>("/reload/logic", {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  reloadWz: (reason: string) =>
    request<WzReloadResponse>("/reload/wz", {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  restart: (reason: string) =>
    request<RestartResponse>("/restart", {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  apiRequestAudits: async (limit = 100, signal?: AbortSignal) =>
    normalizeAuditPage<ApiRequestAudit>(
      await request<Partial<AuditPage<ApiRequestAudit>>>(`/audits/api-requests?limit=${limit}`, { signal }),
      limit,
    ),
  toolExecutionAudits: async (limit = 100, signal?: AbortSignal) =>
    normalizeAuditPage<ToolExecutionAudit>(
      await request<Partial<AuditPage<ToolExecutionAudit>>>(`/audits/tool-executions?limit=${limit}`, { signal }),
      limit,
    ),
  tasks: (limit = 100, signal?: AbortSignal) =>
    request<TasksResponse>(`/tasks?limit=${limit}`, { signal }),
  schedules: (signal?: AbortSignal) => request<SchedulesResponse>("/schedules", { signal }),
  runSchedule: (scheduleId: string, reason: string) =>
    request<BackgroundTaskRun>(`/schedules/${encodeURIComponent(scheduleId)}/run`, {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  setScheduleEnabled: (scheduleId: string, enabled: boolean, reason: string) =>
    request<TaskSchedule>(`/schedules/${encodeURIComponent(scheduleId)}/enabled`, {
      method: "PUT",
      body: JSON.stringify({ enabled }),
      headers: { "X-Admin-Reason": reason },
    }),
  retryTask: (taskId: string, reason: string) =>
    request<BackgroundTaskRun>(`/tasks/${encodeURIComponent(taskId)}/retry`, {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  roles: (signal?: AbortSignal) => request<RolesResponse>("/roles", { signal }),
  createRole: (role: { roleCode: string; displayName: string; description: string; permissions: string }, reason: string) =>
    request<AdminRole>("/roles", {
      method: "POST",
      body: JSON.stringify(role),
      headers: { "X-Admin-Reason": reason },
    }),
  updateRole: (roleId: number, role: { displayName: string; description: string; permissions: string }, reason: string) =>
    request<AdminRole>(`/roles/${roleId}`, {
      method: "PUT",
      body: JSON.stringify(role),
      headers: { "X-Admin-Reason": reason },
    }),
  searchAccounts: (query: string, limit = 20, signal?: AbortSignal) =>
    request<{ accounts: AccountOption[] }>(`/accounts?query=${encodeURIComponent(query)}&limit=${limit}`, { signal }),
  accounts: (query: string, status: "all" | "active" | "banned", offset: number, limit = 20, signal?: AbortSignal) =>
    request<AccountsPageResponse>(`/accounts?query=${encodeURIComponent(query)}&status=${status}&offset=${offset}&limit=${limit}`, { signal }),
  createAccount: (account: CreateAccountInput, reason: string) =>
    request<AdminAccount>("/accounts", {
      method: "POST",
      body: JSON.stringify(account),
      headers: { "X-Admin-Reason": reason },
    }),
  updateAccount: (accountId: number, account: UpdateAccountInput, reason: string) =>
    request<AdminAccount>(`/accounts/${accountId}`, {
      method: "PUT",
      body: JSON.stringify(account),
      headers: { "X-Admin-Reason": reason },
    }),
  deleteAccount: (accountId: number, reason: string) =>
    request<DeleteAccountResponse>(`/accounts/${accountId}`, {
      method: "DELETE",
      headers: { "X-Admin-Reason": reason },
    }),
  account: (accountId: number, signal?: AbortSignal) =>
    request<AccountDetailResponse>(`/accounts/${accountId}`, { signal }),
  character: (accountId: number, characterId: number, signal?: AbortSignal) =>
    request<CharacterAdminDetailResponse>(`/accounts/${accountId}/characters/${characterId}`, { signal }),
  updateAccountRestrictions: (
    accountId: number,
    restrictions: { banned?: boolean; muted?: boolean; banReason?: string },
    reason: string,
  ) => request<{ updated: true; disconnected: number; account: AdminAccount }>(`/accounts/${accountId}/restrictions`, {
    method: "PUT",
    body: JSON.stringify(restrictions),
    headers: { "X-Admin-Reason": reason },
  }),
  forceAccountOffline: (accountId: number, reason: string) =>
    request<{ forcedOffline: true; accountId: number; disconnected: number }>(`/accounts/${accountId}/force-offline`, {
      method: "POST",
      headers: { "X-Admin-Reason": reason },
    }),
  packetTraceCatalog: (signal?: AbortSignal) =>
    request<PacketTraceCatalog>("/packet-traces/catalog", { signal }),
  packetTrace: (characterId: number, afterSequence = 0, limit = 200, signal?: AbortSignal) =>
    request<PacketTraceSnapshot>(`/packet-traces/${characterId}?afterSequence=${afterSequence}&limit=${limit}`, { signal }),
  startPacketTrace: (
    characterId: number,
    config: { mode: PacketTraceFilterMode; directions: PacketTraceDirection[]; opcodes: string[]; maxPayloadBytes: number },
    reason: string,
  ) => request<PacketTraceSnapshot>(`/packet-traces/${characterId}`, {
    method: "PUT",
    body: JSON.stringify(config),
    headers: { "X-Admin-Reason": reason },
  }),
  stopPacketTrace: (characterId: number, reason: string) =>
    request<PacketTraceSnapshot>(`/packet-traces/${characterId}`, {
      method: "DELETE",
      headers: { "X-Admin-Reason": reason },
    }),
  generateTemporaryPassword: (accountId: number, reason: string, durationMinutes = 30) =>
    request<TemporaryPasswordResponse>(`/accounts/${accountId}/temporary-password`, {
      method: "POST",
      body: JSON.stringify({ durationMinutes }),
      headers: { "X-Admin-Reason": reason },
    }),
  accountRoles: (accountId: number, signal?: AbortSignal) =>
    request<AccountRolesResponse>(`/accounts/${accountId}/roles`, { signal }),
  setAccountRoles: (accountId: number, roleIds: number[], reason: string) =>
    request<AccountRolesResponse>(`/accounts/${accountId}/roles`, {
      method: "PUT",
      body: JSON.stringify({ roleIds }),
      headers: { "X-Admin-Reason": reason },
    }),
}

export const adminQueryKeys = {
  health: ["admin", "health"] as const,
  channels: ["admin", "channels"] as const,
  online: ["admin", "online"] as const,
  packetTraceCatalog: ["admin", "packet-trace", "catalog"] as const,
  packetTrace: (characterId: number) => ["admin", "packet-trace", characterId] as const,
  config: ["admin", "config"] as const,
  inFlight: ["admin", "reload", "in-flight"] as const,
  restartPhase: ["admin", "restart", "phase"] as const,
  apiRequestAudits: ["admin", "audits", "api-requests"] as const,
  toolExecutionAudits: ["admin", "audits", "tool-executions"] as const,
  tasks: ["admin", "tasks"] as const,
  schedules: ["admin", "schedules"] as const,
  roles: ["admin", "roles"] as const,
  accounts: (query: string, status: string, offset: number) => ["admin", "accounts", query, status, offset] as const,
  account: (accountId: number) => ["admin", "account", accountId] as const,
  character: (accountId: number, characterId: number) => ["admin", "account", accountId, "character", characterId] as const,
}

function normalizeAuditPage<T>(response: Partial<AuditPage<T>>, requestedLimit: number): AuditPage<T> {
  return {
    total: response.total ?? 0,
    limit: response.limit ?? requestedLimit,
    records: Array.isArray(response.records) ? response.records : [],
  }
}
