import { createContext } from "react"

export interface AdminIdentity {
  accountName: string
  permissions: string[]
}

export interface AdminAuthContextValue {
  token: string
  identity: AdminIdentity | null
  login: (name: string, password: string) => Promise<AdminIdentity>
  logout: () => void
}

export const AdminAuthContext = createContext<AdminAuthContextValue | null>(null)
