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

  it("keeps the only tab open and does not navigate", () => {
    const tabs = [{ routePath: "/", href: "/" }]

    expect(removeOpenTab(tabs, "/", "/")).toEqual({
      tabs,
      navigateTo: null,
    })
  })

  it("closes an inactive tab without changing the active route", () => {
    const tabs = [
      { routePath: "/", href: "/" },
      { routePath: "/players", href: "/players?name=alice" },
    ]

    expect(removeOpenTab(tabs, "/players", "/")).toEqual({
      tabs: [tabs[0]],
      navigateTo: null,
    })
  })

  it("normalizes malformed, cross-origin and mismatched saved hrefs", () => {
    const validPaths = new Set(["/", "/accounts", "/players"])

    expect(parseOpenTabs("not-json", "http://127.0.0.1:5173", validPaths)).toEqual([])
    expect(parseOpenTabs(JSON.stringify([
      { routePath: "/accounts", href: "https://evil.example/accounts?offset=20" },
      { routePath: "/players", href: "/accounts?name=alice" },
      { routePath: "/", href: "/?welcome=true#status" },
    ]), "http://127.0.0.1:5173", validPaths)).toEqual([
      { routePath: "/accounts", href: "/accounts" },
      { routePath: "/players", href: "/players" },
      { routePath: "/", href: "/?welcome=true#status" },
    ])
  })
})
