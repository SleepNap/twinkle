import { ApiError } from "@/api/admin"
import { translate } from "@/i18n"

const ADMIN_API_BASE = "/admin/v1"

export interface AiStatus {
  available: boolean
  model: string
  externalModel: boolean
  callCount: number
  consecutiveFailures: number
  degraded: boolean
  lastError: string
  lastErrorAt: string
  runtimeEnabled: boolean
  allowedModels: string[]
}

export interface AiPolicy {
  accountId: number
  accountName: string
  enabled: boolean
  allowedModels: string
  dailyPointLimit: number
  dailyCallLimit: number
  dailyTokenLimit: number
  dailyPointUsed: number
  dailyCallUsed: number
  dailyTokenUsed: number
  windowStart: string | null
  updatedAt: string | null
  updatedBy: string | null
}

export interface AiPolicyDraft {
  enabled: boolean
  allowedModels: string
  dailyPointLimit: number
  dailyCallLimit: number
  dailyTokenLimit: number
}

export interface AiUsageRecord {
  id: number
  toolName: string
  model: string
  inputTokens: number
  outputTokens: number
  pointsCost: number
  accountId: number | null
  elapsedMs: number
  createdAt: string
}

export interface AiUsageSummary {
  records: AiUsageRecord[]
  totalCalls: number
  totalPoints: number
  totalTokens: number
  limit: number
  truncated: boolean
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
    if (error instanceof DOMException && error.name === "AbortError") throw error
    throw new ApiError(translate("api.unreachable"))
  }
  if (response.status === 401) {
    redirectToLogin()
    throw new ApiError(translate("api.unauthenticated"), 401)
  }
  if (!response.ok) {
    const body = response.headers.get("content-type")?.includes("application/json")
      ? await response.json().catch(() => null) as { error?: string; message?: string } | null
      : null
    throw new ApiError(body?.message ?? body?.error ?? translate("api.httpError", {
      status: response.status,
      statusText: response.statusText,
    }).trim(), response.status)
  }
  return response.json() as Promise<T>
}

export const aiPolicyApi = {
  status: (signal?: AbortSignal) => request<AiStatus>("/ai/status", { signal }),
  policies: (accountId: number | null, signal?: AbortSignal) =>
    request<{ policies: AiPolicy[] }>(
      accountId == null ? "/ai/policies" : `/ai/policies?accountId=${accountId}`,
      { signal },
    ),
  savePolicy: (accountId: number, draft: AiPolicyDraft, reason: string) =>
    request<{ saved: true; policy: AiPolicy; refreshedKeys: number }>(`/ai/policies/${accountId}`, {
      method: "PUT",
      body: JSON.stringify({
        enabled: draft.enabled ? 1 : 0,
        allowedModels: draft.allowedModels,
        dailyPointLimit: draft.dailyPointLimit,
        dailyCallLimit: draft.dailyCallLimit,
        dailyTokenLimit: draft.dailyTokenLimit,
      }),
      headers: { "X-Admin-Reason": reason },
    }),
  usage: (from: string, to: string, accountId: number | null, signal?: AbortSignal) => {
    const params = new URLSearchParams()
    if (from) params.set("from", from)
    if (to) params.set("to", to)
    if (accountId != null) params.set("accountId", String(accountId))
    const query = params.toString()
    return request<AiUsageSummary>(`/ai/usage${query ? `?${query}` : ""}`, { signal })
  },
}

export const aiPolicyQueryKeys = {
  status: ["ai", "status"] as const,
  policies: (accountId: number | null) => ["ai", "policies", accountId] as const,
  usage: (from: string, to: string, accountId: number | null) =>
    ["ai", "usage", from, to, accountId] as const,
}
