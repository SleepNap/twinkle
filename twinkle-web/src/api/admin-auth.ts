import { translate } from "@/i18n"

const ADMIN_AUTH_BASE = "/admin/v1/auth"

export interface AdminLoginResponse {
  token: string
  accountName: string
  permissions: string[]
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${ADMIN_AUTH_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        ...init.headers,
      },
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error
    throw new Error(translate("api.unreachable"))
  }
  if (!response.ok) {
    const body = response.headers.get("content-type")?.includes("application/json")
      ? await response.json().catch(() => null) as { error?: string } | null
      : null
    if (response.status === 401) {
      throw new Error(translate("auth.loginFailed"))
    }
    throw new Error(body?.error ?? translate("api.httpError", {
      status: response.status,
      statusText: response.statusText,
    }).trim())
  }
  return response.json() as Promise<T>
}

export const adminAuthApi = {
  login: (name: string, password: string) =>
    request<AdminLoginResponse>("/login", { method: "POST", body: JSON.stringify({ name, password }) }),
  logout: (token: string) =>
    request<{ loggedOut: boolean }>("/logout", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    }),
}
