import { ApiError } from "@/api/admin"
import { translate, type MessageKey } from "@/i18n"

const CAPABILITY_API_BASE = "/api/v1"

export const supportedScopes = [
  "server.health:read",
  "player.online:read",
  "player.inventory:read",
  "game:read",
  "game:write",
  "ai:use",
  "keys:manage",
  "events:read",
  "events:write",
] as const

export type ApiScope = (typeof supportedScopes)[number]

export interface IdentityResponse {
  contractVersion: string
  subject: { subjectId: string; displayName: string }
  credential: { credentialId: string; type: "api_key"; expiresAt: string | null }
  server: Record<string, unknown>
  effectiveScopes: string[]
  permissionVersion: string
  generatedAt: string
}

export interface ApiKeySummary {
  id: number
  credentialId: string
  keyPrefix: string
  displayName: string
  subjectId: string
  ownerAccountId: number | null
  scopes: string[]
  serverId: string
  createdAt: string
  expiresAt: string | null
  disabledAt: string | null
  revokedAt: string | null
  rotatedFromPrefix: string | null
  lastUsedAt: string | null
}

export interface IssuedApiKey {
  id: number
  credentialId: string
  keyPrefix: string
  token: string
  displayName: string
  subjectId: string
  scopes: string[]
  serverId: string
  createdAt: string
  expiresAt: string | null
  rotatedFromPrefix: string | null
}

export interface IssueApiKeyInput {
  displayName: string
  ownerAccountId: number | null
  scopes: string[]
  expiresAt: string | null
}

export interface AccountSummary {
  id: number
  name: string
  banned: boolean
  gender: number
  characterslots: number
}

export interface CharacterSummary {
  id: number
  name: string
  level: number
  job: number
  map: number
  meso: number
}

async function capabilityRequest<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${CAPABILITY_API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${token}`,
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error
    throw new ApiError(translate("api.capabilityUnreachable"))
  }

  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? ""
    const body = contentType.includes("application/json")
      ? await response.json().catch(() => null) as { error?: string; code?: string; message?: string } | null
      : null
    const message = body?.message ?? capabilityErrorMessage(body?.code ?? body?.error)
    throw new ApiError(message ?? translate("api.capabilityHttpError", {
      status: response.status,
      statusText: response.statusText,
    }).trim(), response.status)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function capabilityErrorMessage(code?: string) {
  const messages: Record<string, MessageKey> = {
    unauthenticated: "api.unauthenticated",
    permission_denied: "api.permissionDenied",
    api_key_not_found: "api.keyNotFound",
    invalid_key_request: "api.invalidKeyRequest",
    account_not_found: "api.accountNotFound",
  }
  return code ? (messages[code] ? translate(messages[code]) : code) : undefined
}

export const capabilityApi = {
  identity: (token: string, signal?: AbortSignal) =>
    capabilityRequest<IdentityResponse>("/identity/me", token, { signal }),
  keys: (token: string, signal?: AbortSignal) =>
    capabilityRequest<ApiKeySummary[]>("/auth/keys", token, { signal }),
  issueKey: (token: string, input: IssueApiKeyInput) =>
    capabilityRequest<IssuedApiKey>("/auth/keys", token, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  disableKey: (token: string, prefix: string) =>
    capabilityRequest<void>(`/auth/keys/${encodeURIComponent(prefix)}/disable`, token, { method: "POST" }),
  enableKey: (token: string, prefix: string) =>
    capabilityRequest<void>(`/auth/keys/${encodeURIComponent(prefix)}/enable`, token, { method: "POST" }),
  rotateKey: (token: string, prefix: string) =>
    capabilityRequest<IssuedApiKey>(`/auth/keys/${encodeURIComponent(prefix)}/rotate`, token, { method: "POST" }),
  updateKeyScopes: (token: string, prefix: string, scopes: string[]) =>
    capabilityRequest<ApiKeySummary>(`/auth/keys/${encodeURIComponent(prefix)}/scopes`, token, {
      method: "PUT",
      body: JSON.stringify({ scopes }),
    }),
  revokeKey: (token: string, prefix: string) =>
    capabilityRequest<void>(`/auth/keys/${encodeURIComponent(prefix)}`, token, { method: "DELETE" }),
  account: (token: string, name: string, signal?: AbortSignal) =>
    capabilityRequest<AccountSummary>(`/account/${encodeURIComponent(name)}`, token, { signal }),
  characters: (token: string, accountId: number, signal?: AbortSignal) =>
    capabilityRequest<CharacterSummary[]>(`/account/${accountId}/characters`, token, { signal }),
}

export const capabilityQueryKeys = {
  identity: ["capability", "identity"] as const,
  keys: ["capability", "keys"] as const,
  account: (name: string) => ["capability", "account", name] as const,
  characters: (accountId: number) => ["capability", "account", accountId, "characters"] as const,
}
