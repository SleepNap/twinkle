import { ApiError } from "@/api/admin"
import { translate } from "@/i18n"

const ADMIN_API_BASE = "/admin/v1"

export interface BillingAccountSummary {
  accountId: number
  name: string
  balance: number
  planId: number | null
}

export interface BillingAccountDetail {
  accountId: number
  name: string
  balance: number
  planId: number | null
  planCode: string | null
  monthlyLimit: number | null
  monthlyUsed: number | null
  weeklyLimit: number | null
  weeklyUsed: number | null
  fiveHourLimit: number | null
  fiveHourUsed: number | null
}

export interface PointTransaction {
  id: number
  accountId: number
  changeAmount: number
  balanceAfter: number
  reason: string
  detail: string | null
  createdAt: string
}

export interface SubscriptionPlan {
  id: number
  planCode: string
  displayName: string
  monthlyLimit: number
  weeklyLimit: number
  fiveHourLimit: number
  priceNx: number
  enabled: number
}

export interface AccountOption {
  id: number
  name: string
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

export const billingApi = {
  accounts: (signal?: AbortSignal) =>
    request<{ accounts: BillingAccountSummary[] }>("/billing/accounts", { signal }),
  account: (accountId: number, signal?: AbortSignal) =>
    request<BillingAccountDetail>(`/billing/accounts/${accountId}`, { signal }),
  adjust: (accountId: number, amount: number, note: string | undefined, reason: string) =>
    request<{ adjusted: true }>(`/billing/accounts/${accountId}/adjust`, {
      method: "POST",
      body: JSON.stringify({ amount, reason: note }),
      headers: { "X-Admin-Reason": reason },
    }),
  setPlan: (accountId: number, planId: number | null, reason: string) =>
    request<{ set: true }>(`/billing/accounts/${accountId}/plan`, {
      method: "POST",
      body: JSON.stringify({ planId }),
      headers: { "X-Admin-Reason": reason },
    }),
  transactions: (accountId: number, signal?: AbortSignal) =>
    request<{ transactions: PointTransaction[] }>(`/billing/transactions?accountId=${accountId}`, { signal }),
  plans: (signal?: AbortSignal) =>
    request<{ plans: SubscriptionPlan[] }>("/billing/plans", { signal }),
  upsertPlan: (plan: Partial<SubscriptionPlan> & { planCode: string }, reason: string) =>
    request<SubscriptionPlan>("/billing/plans", {
      method: "POST",
      body: JSON.stringify(plan),
      headers: { "X-Admin-Reason": reason },
    }),
  searchAccounts: (query: string, limit = 20, signal?: AbortSignal) =>
    request<{ accounts: AccountOption[] }>(`/accounts?query=${encodeURIComponent(query)}&limit=${limit}`, { signal }),
}

export const billingQueryKeys = {
  accounts: ["billing", "accounts"] as const,
  account: (accountId: number) => ["billing", "account", accountId] as const,
  transactions: (accountId: number) => ["billing", "transactions", accountId] as const,
  plans: ["billing", "plans"] as const,
}
