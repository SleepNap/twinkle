import { useCallback, useMemo, useState, type ReactNode } from "react"

import { capabilityApi, type IdentityResponse } from "@/api/capability"
import { CredentialContext } from "@/auth/credential-context"
import { useI18n } from "@/i18n"

const SESSION_KEY = "twinkle.console.api-key"

function initialToken() {
  if (typeof window === "undefined") return ""
  return window.sessionStorage.getItem(SESSION_KEY) ?? ""
}

export function CredentialProvider({ children }: { children: ReactNode }) {
  const { t } = useI18n()
  const [token, setToken] = useState(initialToken)
  const [identity, setIdentity] = useState<IdentityResponse | null>(null)

  const connect = useCallback(async (candidate: string) => {
    const normalized = candidate.trim()
    if (!normalized) throw new Error(t("auth.credentialRequired"))
    const verified = await capabilityApi.identity(normalized)
    window.sessionStorage.setItem(SESSION_KEY, normalized)
    setToken(normalized)
    setIdentity(verified)
    return verified
  }, [t])

  const disconnect = useCallback(() => {
    window.sessionStorage.removeItem(SESSION_KEY)
    setToken("")
    setIdentity(null)
  }, [])

  const value = useMemo(() => ({ token, identity, connect, disconnect }), [connect, disconnect, identity, token])
  return <CredentialContext.Provider value={value}>{children}</CredentialContext.Provider>
}
