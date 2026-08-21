export interface OpenTab {
  routePath: string
  href: string
}

export function upsertOpenTab(tabs: OpenTab[], incoming: OpenTab): OpenTab[] {
  const index = tabs.findIndex((tab) => tab.routePath === incoming.routePath)
  if (index < 0) return [...tabs, incoming]
  if (tabs[index].href === incoming.href) return tabs
  return tabs.map((tab, tabIndex) => tabIndex === index ? incoming : tab)
}

export function removeOpenTab(tabs: OpenTab[], routePath: string, activePath: string) {
  const closingIndex = tabs.findIndex((tab) => tab.routePath === routePath)
  if (closingIndex < 0 || tabs.length <= 1) return { tabs, navigateTo: null }

  const remaining = tabs.filter((tab) => tab.routePath !== routePath)
  if (activePath !== routePath) return { tabs: remaining, navigateTo: null }
  return {
    tabs: remaining,
    navigateTo: remaining[Math.min(closingIndex, remaining.length - 1)].href,
  }
}

export function parseOpenTabs(serialized: string | null, origin: string, validPaths: ReadonlySet<string>): OpenTab[] {
  try {
    const value: unknown = JSON.parse(serialized ?? "[]")
    if (!Array.isArray(value)) return []

    const seen = new Set<string>()
    return value.flatMap((entry): OpenTab[] => {
      if (!entry || typeof entry !== "object") return []
      const routePath = "routePath" in entry ? entry.routePath : null
      const href = "href" in entry ? entry.href : null
      if (typeof routePath !== "string" || typeof href !== "string") return []
      if (!validPaths.has(routePath) || seen.has(routePath)) return []
      seen.add(routePath)
      return [{ routePath, href: normalizeRouteHref(routePath, href, origin) }]
    })
  } catch {
    return []
  }
}

function normalizeRouteHref(routePath: string, href: string, origin: string): string {
  try {
    const url = new URL(href, origin)
    if (url.origin !== origin || url.pathname !== routePath) return routePath
    return `${url.pathname}${url.search}${url.hash}`
  } catch {
    return routePath
  }
}
