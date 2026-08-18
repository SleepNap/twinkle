import { Activity, ChevronDown, Coins, FileSearch, Globe2, KeyRound, ListTodo, LogOut, Radio, Server, Settings2, ShieldCheck, UserRoundSearch, Users, Wrench } from "lucide-react"
import { NavLink, Outlet, useNavigate } from "react-router-dom"
import { Suspense, useState } from "react"

import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"
import { supportedLocales, useI18n, type MessageKey } from "@/i18n"
import { useCredential } from "@/auth/use-credential"
import { useAdminAuth } from "@/auth/use-admin-auth"

interface NavItem {
  to: string
  label: string
  icon: typeof Activity
  end?: boolean
}

interface NavGroup {
  key: string
  label: string
  items: NavItem[]
}

const navigationGroups: NavGroup[] = [
  {
    key: "monitoring",
    label: "nav.group.monitoring",
    items: [
      { to: "/", label: "nav.overview", icon: Activity, end: true },
      { to: "/channels", label: "nav.channels", icon: Radio },
      { to: "/players", label: "nav.players", icon: Users },
      { to: "/tasks", label: "nav.tasks", icon: ListTodo },
    ],
  },
  {
    key: "players",
    label: "nav.group.players",
    items: [
      { to: "/accounts", label: "nav.accounts", icon: UserRoundSearch },
      { to: "/billing", label: "nav.billing", icon: Coins },
    ],
  },
  {
    key: "operations",
    label: "nav.group.operations",
    items: [
      { to: "/config", label: "nav.config", icon: Settings2 },
      { to: "/operations", label: "nav.operations", icon: Wrench },
    ],
  },
  {
    key: "security",
    label: "nav.group.security",
    items: [
      { to: "/api-keys", label: "nav.apiKeys", icon: KeyRound },
      { to: "/roles", label: "nav.roles", icon: ShieldCheck },
      { to: "/audits", label: "nav.audits", icon: FileSearch },
    ],
  },
]

export function AppShell() {
  const { locale, setLocale, t } = useI18n()
  const { token } = useCredential()
  const { identity, logout } = useAdminAuth()
  const navigate = useNavigate()
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})

  function handleLogout() {
    logout()
    navigate("/login", { replace: true })
  }

  function toggleGroup(key: string) {
    setCollapsed((current) => ({ ...current, [key]: !current[key] }))
  }

  return (
    <div className="min-h-svh bg-muted/30 text-foreground">
      <header className="sticky top-0 z-20 border-b bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-7xl items-center px-4 sm:px-6">
          <NavLink to="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <span className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
              <Server className="size-4" />
            </span>
            Twinkle
          </NavLink>
          <Badge variant="secondary" className="ml-2 hidden font-normal sm:inline-flex">
            {t("app.console")}
          </Badge>
          <Badge variant={token ? "secondary" : "outline"} className="ml-auto hidden font-normal sm:flex">
            {token ? t("api.connected") : t("api.disconnected")}
          </Badge>
          <label className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground sm:ml-3">
            <Globe2 className="hidden size-3.5 sm:block" />
            <span className="sr-only">{t("language.label")}</span>
            <select
              value={locale}
              onChange={(event) => setLocale(event.target.value as typeof locale)}
              className="rounded-md border bg-background px-2 py-1 text-xs text-foreground"
              aria-label={t("language.label")}
            >
              {supportedLocales.map((item) => (
                <option key={item} value={item}>{item === "zh-CN" ? "简体中文" : "English"}</option>
              ))}
            </select>
          </label>
          {identity && (
            <button
              type="button"
              onClick={handleLogout}
              className={cn(buttonVariants({ variant: "ghost", size: "sm" }), "ml-1")}
            >
              <LogOut data-icon="inline-start" className="size-4" />
              {t("auth.signOut")}
            </button>
          )}
        </div>
      </header>

      <div className="mx-auto grid max-w-7xl md:grid-cols-[13rem_1fr]">
        <aside className="min-w-0 border-b bg-background px-3 py-3 md:min-h-[calc(100svh-3.5rem)] md:border-r md:border-b-0 md:py-5">
          <nav aria-label={t("app.navigation")} className="flex flex-col gap-2">
            {navigationGroups.map((group) => {
              const isCollapsed = collapsed[group.key] ?? false
              return (
                <div key={group.key}>
                  <button
                    type="button"
                    onClick={() => toggleGroup(group.key)}
                    aria-expanded={!isCollapsed}
                    className="flex w-full items-center gap-1 rounded-md px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
                  >
                    <ChevronDown className={cn("size-3.5 transition-transform", isCollapsed && "-rotate-90")} />
                    {t(group.label as MessageKey)}
                  </button>
                  {!isCollapsed && (
                    <div className="flex flex-col gap-1">
                      {group.items.map(({ to, label, icon: Icon, end }) => (
                        <NavLink
                          key={to}
                          to={to}
                          end={end}
                          className={({ isActive }) =>
                            cn(
                              buttonVariants({ variant: isActive ? "secondary" : "ghost" }),
                              "shrink-0 justify-start",
                            )
                          }
                        >
                          <Icon data-icon="inline-start" />
                          {t(label as MessageKey)}
                        </NavLink>
                      ))}
                    </div>
                  )}
                </div>
              )
            })}
          </nav>
          <Separator className="my-4 hidden md:block" />
          <p className="hidden px-2 text-xs leading-5 text-muted-foreground md:block">
            {t("app.dataHint")}
          </p>
        </aside>

        <main className="min-w-0 px-4 py-6 sm:px-6 lg:px-8">
          <Suspense fallback={<PageFallback />}>
            <Outlet />
          </Suspense>
        </main>
      </div>
    </div>
  )
}

function PageFallback() {
  return (
    <div className="grid gap-4">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-4 w-80 max-w-full" />
      <Skeleton className="mt-2 h-48 w-full" />
    </div>
  )
}
