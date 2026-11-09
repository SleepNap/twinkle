import { Activity as ReactActivity, Suspense, useEffect, useMemo, useState } from "react"
import { ChevronDown, Globe2, LogOut, Server, X } from "lucide-react"
import { NavLink, useLocation, useNavigate } from "react-router-dom"

import { useAdminAuth } from "@/auth/use-admin-auth"
import { useCredential } from "@/auth/use-credential"
import { Badge } from "@/components/ui/badge"
import { buttonVariants } from "@/components/ui/button"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Skeleton } from "@/components/ui/skeleton"
import {
  navigationGroups,
  primaryNavigationPaths,
  workspaceRoutesByPath,
  type WorkspaceRouteDefinition,
} from "@/components/workspace-navigation"
import { NotFoundPage } from "@/components/workspace-pages"
import { parseOpenTabs, removeOpenTab, upsertOpenTab, type OpenTab } from "@/components/workspace-tabs"
import { supportedLocales, useI18n } from "@/i18n"
import { cn } from "@/lib/utils"

const OPEN_TABS_STORAGE_KEY = "twinkle.console.open-tabs.v1"

export function AppShell() {
  const { locale, setLocale, t } = useI18n()
  const { token } = useCredential()
  const { identity, logout } = useAdminAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const currentRoute = workspaceRoutesByPath.get(location.pathname)
  const currentHref = `${location.pathname}${location.search}${location.hash}`
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})
  const [openTabs, setOpenTabs] = useState<OpenTab[]>(() => {
    const restored = restoreOpenTabs()
    if (!currentRoute) return restored
    return upsertOpenTab(restored, { routePath: currentRoute.path, href: currentHref })
  })

  useEffect(() => {
    if (!currentRoute) return
    // 路由是工作区的外部状态源，变化时必须把新页面纳入持久页签集合。
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setOpenTabs((current) => upsertOpenTab(current, { routePath: currentRoute.path, href: currentHref }))
  }, [currentHref, currentRoute])

  useEffect(() => {
    window.sessionStorage.setItem(OPEN_TABS_STORAGE_KEY, JSON.stringify(openTabs))
  }, [openTabs])

  const displayedTabs = useMemo(() => {
    if (!currentRoute) return openTabs
    return upsertOpenTab(openTabs, { routePath: currentRoute.path, href: currentHref })
  }, [currentHref, currentRoute, openTabs])

  async function handleLogout() {
    window.sessionStorage.removeItem(OPEN_TABS_STORAGE_KEY)
    window.localStorage.removeItem(OPEN_TABS_STORAGE_KEY)
    await logout()
    window.location.replace("/login")
  }

  function toggleGroup(key: string) {
    setCollapsed((current) => ({ ...current, [key]: !current[key] }))
  }

  function closeTab(routePath: string) {
    const result = removeOpenTab(displayedTabs, routePath, location.pathname)
    if (result.tabs === displayedTabs) return
    setOpenTabs(result.tabs)
    if (result.navigateTo) navigate(result.navigateTo)
  }

  return (
    <div className="min-h-svh bg-background text-foreground">
      <header className="sticky top-0 z-30 border-b border-border bg-background/95 backdrop-blur">
        <div className="mx-auto flex h-14 max-w-[1600px] items-center px-4 sm:px-6 lg:px-7">
          <NavLink to="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <span className="flex size-7 items-center justify-center rounded-lg bg-primary text-primary-foreground">
              <Server className="size-4" />
            </span>
            Twinkle
          </NavLink>
          <Badge variant="secondary" className="ml-2 hidden font-normal sm:inline-flex">
            {t("app.console")}
          </Badge>
          <span className="ml-3 hidden items-center gap-2 text-xs text-muted-foreground md:flex">
            <span className="hidden sm:inline">{t("app.server")}</span>
            <span className="hidden sm:inline">/</span>
            <span className="truncate text-foreground/80">
              {currentRoute ? t(currentRoute.label) : t("app.console")}
            </span>
          </span>
          <Badge variant={token ? "secondary" : "outline"} className="ml-auto hidden font-normal sm:flex">
            {token ? t("api.connected") : t("api.disconnected")}
          </Badge>
          <div className="ml-auto flex items-center gap-1.5 text-xs text-muted-foreground sm:ml-3">
            <Globe2 className="hidden size-3.5 sm:block" />
            <span className="sr-only">{t("language.label")}</span>
            <Select
              value={locale}
              onValueChange={(value) => setLocale(value as typeof locale)}
            >
              <SelectTrigger size="sm" className="w-24 text-xs" aria-label={t("language.label")}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent align="end">
                {supportedLocales.map((item) => (
                  <SelectItem key={item} value={item}>{item === "zh-CN" ? "简体中文" : "English"}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
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

      <div className="mx-auto grid max-w-[1600px] md:grid-cols-[13.25rem_minmax(0,1fr)]">
        <aside className="min-w-0 border-b border-border bg-sidebar px-3 py-3 md:sticky md:top-14 md:min-h-[calc(100svh-3.5rem)] md:max-h-[calc(100svh-3.5rem)] md:overflow-y-auto md:border-r md:border-b-0 md:py-4">
          <nav aria-label={t("app.navigation")} className="flex flex-col gap-1">
            {primaryNavigationPaths.map((path) => (
              <NavigationRouteLink key={path} route={workspaceRoutesByPath.get(path)!} />
            ))}
            <Separator className="my-1" />
            {navigationGroups.map((group) => {
              const isCollapsed = collapsed[group.key] ?? false
              return (
                <div key={group.key}>
                  <button
                    type="button"
                    onClick={() => toggleGroup(group.key)}
                    aria-expanded={!isCollapsed}
                    className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-xs font-medium tracking-wide text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
                  >
                    <ChevronDown className={cn("size-3.5 transition-transform", isCollapsed && "-rotate-90")} />
                    <span className="min-w-0 whitespace-nowrap text-left leading-5">{t(group.label)}</span>
                  </button>
                  {!isCollapsed && (
                    <div className="ml-2.5 flex flex-col gap-0.5 border-l border-sidebar-border pl-1.5">
                      {group.routePaths.map((path) => (
                        <NavigationRouteLink key={path} route={workspaceRoutesByPath.get(path)!} />
                      ))}
                      {group.externalItems?.map(({ href, label, icon: Icon }) => (
                        <a
                          key={href}
                          href={href}
                          target="_blank"
                          rel="noreferrer"
                          className={cn(buttonVariants({ variant: "ghost" }), "shrink-0 justify-start")}
                        >
                          <Icon data-icon="inline-start" />
                          {t(label)}
                        </a>
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

        <section className="min-w-0">
          {displayedTabs.length > 0 && (
            <div className="sticky top-14 z-20 border-b border-border bg-background/95 px-4 sm:px-6 lg:px-7">
              <div role="tablist" aria-label={t("app.navigation")} className="flex min-w-0 gap-1 overflow-x-auto">
                {displayedTabs.map((tab) => {
                  const route = workspaceRoutesByPath.get(tab.routePath)!
                  const Icon = route.icon
                  const active = location.pathname === route.path
                  return (
                    <div
                      key={route.path}
                      className={cn(
                        "group flex h-9 shrink-0 items-center border-b-2 text-sm transition-colors",
                        active
                          ? "border-foreground text-foreground"
                          : "border-transparent text-muted-foreground hover:border-border hover:text-foreground",
                      )}
                    >
                      <button
                        type="button"
                        role="tab"
                        aria-selected={active}
                        onClick={() => navigate(tab.href)}
                        onAuxClick={(event) => {
                          if (event.button === 1) closeTab(route.path)
                        }}
                        className="flex h-full items-center gap-1.5 pl-3 pr-2 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                      >
                        <Icon className="size-3.5" />
                        {t(route.label)}
                      </button>
                      <button
                        type="button"
                        onClick={() => closeTab(route.path)}
                        disabled={displayedTabs.length <= 1}
                        aria-label={`${t("common.close")} ${t(route.label)}`}
                        className="mr-1 flex size-6 items-center justify-center rounded-sm text-muted-foreground outline-none hover:bg-background hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-30"
                      >
                        <X className="size-3.5" />
                      </button>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          <main className="admin-content mx-auto w-full max-w-[1600px] flex-1 px-4 py-4 sm:px-6 lg:px-7 lg:py-5">
            {displayedTabs.map((tab) => {
              const route = workspaceRoutesByPath.get(tab.routePath)!
              const Page = route.component
              return (
                <ReactActivity key={route.path} mode={location.pathname === route.path ? "visible" : "hidden"}>
                  <Suspense fallback={<PageFallback />}>
                    <Page />
                  </Suspense>
                </ReactActivity>
              )
            })}
            {!currentRoute && (
              <Suspense fallback={<PageFallback />}>
                <NotFoundPage />
              </Suspense>
            )}
          </main>
        </section>
      </div>
    </div>
  )
}

function NavigationRouteLink({ route }: { route: WorkspaceRouteDefinition }) {
  const { t } = useI18n()
  return (
    <NavLink
      to={route.path}
      end={route.path === "/"}
      className={({ isActive }) =>
        cn(
          buttonVariants({ variant: "ghost", size: "sm" }),
          "h-8 shrink-0 justify-start px-2.5 text-sm",
          isActive && "bg-sidebar-accent text-sidebar-accent-foreground hover:bg-sidebar-accent",
        )
      }
    >
      <route.icon data-icon="inline-start" />
      {t(route.label)}
    </NavLink>
  )
}

function restoreOpenTabs(): OpenTab[] {
  // 清理旧版跨会话存储，页签只能存在于当前管理员登录会话。
  window.localStorage.removeItem(OPEN_TABS_STORAGE_KEY)
  return parseOpenTabs(
    window.sessionStorage.getItem(OPEN_TABS_STORAGE_KEY),
    window.location.origin,
    new Set(workspaceRoutesByPath.keys()),
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
