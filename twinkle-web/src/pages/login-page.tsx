import { useState, type FormEvent } from "react"
import { useNavigate } from "react-router-dom"
import { Loader2, Server } from "lucide-react"

import { useAdminAuth } from "@/auth/use-admin-auth"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { useI18n } from "@/i18n"

export function LoginPage() {
  const { t } = useI18n()
  const { login } = useAdminAuth()
  const navigate = useNavigate()
  const [name, setName] = useState("")
  const [password, setPassword] = useState("")
  const [pending, setPending] = useState(false)
  const [error, setError] = useState("")

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim() || !password) return
    setPending(true)
    setError("")
    try {
      await login(name.trim(), password)
      navigate("/", { replace: true })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("auth.loginFailed"))
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-muted/30 px-4">
      <form onSubmit={submit} className="grid w-full max-w-sm gap-5 rounded-xl border bg-background p-6 shadow-sm">
        <div className="flex items-center gap-2 font-semibold tracking-tight">
          <span className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Server className="size-4" />
          </span>
          Twinkle
        </div>
        <div className="grid gap-1.5">
          <h1 className="text-lg font-semibold">{t("auth.signIn")}</h1>
          <p className="text-sm text-muted-foreground">{t("auth.signInDescription")}</p>
        </div>
        <div className="grid gap-2">
          <Label htmlFor="login-name">{t("auth.accountName")}</Label>
          <Input
            id="login-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            autoComplete="username"
            autoFocus
          />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="login-password">{t("auth.password")}</Label>
          <Input
            id="login-password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
          />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="submit" disabled={pending || !name.trim() || !password}>
          {pending && <Loader2 data-icon="inline-start" className="animate-spin" />}
          {pending ? t("common.processing") : t("auth.signIn")}
        </Button>
      </form>
    </div>
  )
}
