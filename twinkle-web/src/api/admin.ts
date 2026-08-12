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

export interface ScriptReloadResponse {
  changed: number
}

export interface LogicReloadResponse {
  safeSwitched: number
  interrupted: number
  newVersion: number
}

export interface RestartResponse extends RestartPhaseResponse {
  accepted: true
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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response

  try {
    response = await fetch(`${ADMIN_API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
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

  if (!response.ok) {
    const body = contentType(response).includes("application/json")
      ? await response.json().catch(() => null) as { error?: string } | null
      : null
    throw new ApiError(
      humanizeApiError(body?.error) ?? translate("api.httpError", {
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
  setConfig: (key: string, value: string) =>
    request<ConfigSetResponse>("/config", {
      method: "POST",
      body: JSON.stringify({ key, value }),
    }),
  inFlight: (signal?: AbortSignal) => request<InFlightResponse>("/reload/in-flight", { signal }),
  restartPhase: (signal?: AbortSignal) =>
    request<RestartPhaseResponse>("/restart/phase", { signal }),
  kick: (characterId: number) =>
    request<KickResponse>("/kick", {
      method: "POST",
      body: JSON.stringify({ characterId }),
    }),
  reloadScripts: () => request<ScriptReloadResponse>("/reload/scripts", { method: "POST" }),
  reloadLogic: () => request<LogicReloadResponse>("/reload/logic", { method: "POST" }),
  restart: () => request<RestartResponse>("/restart", { method: "POST" }),
  apiRequestAudits: (limit = 100, signal?: AbortSignal) =>
    request<AuditPage<ApiRequestAudit>>(`/audits/api-requests?limit=${limit}`, { signal }),
  toolExecutionAudits: (limit = 100, signal?: AbortSignal) =>
    request<AuditPage<ToolExecutionAudit>>(`/audits/tool-executions?limit=${limit}`, { signal }),
  tasks: (limit = 100, signal?: AbortSignal) =>
    request<TasksResponse>(`/tasks?limit=${limit}`, { signal }),
  schedules: (signal?: AbortSignal) => request<SchedulesResponse>("/schedules", { signal }),
  runSchedule: (scheduleId: string) =>
    request<BackgroundTaskRun>(`/schedules/${encodeURIComponent(scheduleId)}/run`, { method: "POST" }),
  setScheduleEnabled: (scheduleId: string, enabled: boolean) =>
    request<TaskSchedule>(`/schedules/${encodeURIComponent(scheduleId)}/enabled`, {
      method: "PUT",
      body: JSON.stringify({ enabled }),
    }),
  retryTask: (taskId: string) =>
    request<BackgroundTaskRun>(`/tasks/${encodeURIComponent(taskId)}/retry`, { method: "POST" }),
}

export const adminQueryKeys = {
  health: ["admin", "health"] as const,
  channels: ["admin", "channels"] as const,
  online: ["admin", "online"] as const,
  config: ["admin", "config"] as const,
  inFlight: ["admin", "reload", "in-flight"] as const,
  restartPhase: ["admin", "restart", "phase"] as const,
  apiRequestAudits: ["admin", "audits", "api-requests"] as const,
  toolExecutionAudits: ["admin", "audits", "tool-executions"] as const,
  tasks: ["admin", "tasks"] as const,
  schedules: ["admin", "schedules"] as const,
}
