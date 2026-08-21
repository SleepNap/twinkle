import { describe, expect, it } from "vitest"

import { parseOpenTabs, removeOpenTab, upsertOpenTab } from "@/components/workspace-tabs"

describe("workspace tabs", () => {
  it("adds a route once and keeps its latest href", () => {
    const initial = [{ routePath: "/accounts", href: "/accounts" }]

    const added = upsertOpenTab(initial, { routePath: "/tasks", href: "/tasks" })
    const updated = upsertOpenTab(added, { routePath: "/accounts", href: "/accounts?offset=20" })

    expect(updated).toEqual([
      { routePath: "/accounts", href: "/accounts?offset=20" },
      { routePath: "/tasks", href: "/tasks" },
    ])
  })

  it("selects an adjacent tab when the active tab closes", () => {
    const tabs = [
      { routePath: "/", href: "/" },
      { routePath: "/players", href: "/players" },
      { routePath: "/accounts", href: "/accounts" },
    ]

    expect(removeOpenTab(tabs, "/players", "/players")).toEqual({
      tabs: [tabs[0], tabs[2]],
      navigateTo: "/accounts",
    })
    expect(removeOpenTab(tabs, "/accounts", "/accounts").navigateTo).toBe("/players")
  })

  it("restores only known same-origin route hrefs", () => {
    const restored = parseOpenTabs(JSON.stringify([
      { routePath: "/", href: "https://example.com/" },
      { routePath: "/accounts", href: "/accounts?offset=20" },
      { routePath: "/accounts", href: "/accounts?offset=40" },
      { routePath: "/missing", href: "/missing" },
    ]), "http://127.0.0.1:5173", new Set(["/", "/accounts"]))

    expect(restored).toEqual([
      { routePath: "/", href: "/" },
      { routePath: "/accounts", href: "/accounts?offset=20" },
    ])
  })
})
