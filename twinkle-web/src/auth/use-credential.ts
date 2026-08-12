import { useContext } from "react"

import { CredentialContext } from "@/auth/credential-context"
import { translate } from "@/i18n"

export function useCredential() {
  const context = useContext(CredentialContext)
  if (!context) throw new Error(translate("auth.providerRequired"))
  return context
}
