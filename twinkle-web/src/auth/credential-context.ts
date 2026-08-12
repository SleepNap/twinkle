import { createContext } from "react"

import type { IdentityResponse } from "@/api/capability"

export interface CredentialContextValue {
  token: string
  identity: IdentityResponse | null
  connect: (token: string) => Promise<IdentityResponse>
  disconnect: () => void
}

export const CredentialContext = createContext<CredentialContextValue | null>(null)
