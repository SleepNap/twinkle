import { useCallback, useMemo, useState, type ReactNode } from "react"

import { adminAuthApi } from "@/api/admin-auth"
import { AdminAuthContext, type AdminIdentity } from "@/auth/admin-auth-context"

const SESSION_KEY = "twinkle.console.admin-session"
const IDENTITY_KEY = "twinkle.console.admin-identity"

function initialToken(): string {
  if (typeof window === "undefined") return ""
  return window.sessionStorage.getItem(SESSION_KEY) ?? ""
}

function initialIdentity(): AdminIdentity | null {
  if (typeof window === "undefined") return null
  const raw = window.sessionStorage.getItem(IDENTITY_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AdminIdentity
  } catch {
    return null
  }
}

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState(initialToken)
  const [identity, setIdentity] = useState<AdminIdentity | null>(initialIdentity)

  const login = useCallback(async (name: string, password: string) => {
    const result = await adminAuthApi.login(name, password)
    window.sessionStorage.setItem(SESSION_KEY, result.token)
    const next: AdminIdentity = { accountName: result.accountName, permissions: result.permissions }
    window.sessionStorage.setItem(IDENTITY_KEY, JSON.stringify(next))
    setToken(result.token)
    setIdentity(next)
    return next
  }, [])

  const logout = useCallback(() => {
    const current = window.sessionStorage.getItem(SESSION_KEY)
    window.sessionStorage.removeItem(SESSION_KEY)
    window.sessionStorage.removeItem(IDENTITY_KEY)
    setToken("")
    setIdentity(null)
    if (current) void adminAuthApi.logout(current).catch(() => {})
  }, [])

  const value = useMemo(() => ({ token, identity, login, logout }), [token, identity, login, logout])
  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>
}
