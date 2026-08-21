import { afterEach, describe, expect, it, vi } from "vitest"

import { capabilityApi } from "@/api/capability"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("capabilityApi", () => {
  it("使用 Bearer 凭据执行身份预检", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      contractVersion: "1",
      subject: { subjectId: "owner", displayName: "Owner" },
      credential: { credentialId: "bootstrap", type: "api_key", expiresAt: null },
      server: {},
      effectiveScopes: ["*"],
      permissionVersion: "v1",
      generatedAt: "2026-08-12T00:00:00Z",
    }))
    vi.stubGlobal("fetch", fetchMock)

    await capabilityApi.identity("secret-management-key")

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/identity/me", expect.objectContaining({
      headers: expect.objectContaining({ Authorization: "Bearer secret-management-key" }),
    }))
  })

  it("按签发契约提交名称、账号、Scope 和有效期", async () => {
    const input = {
      displayName: "public api client",
      ownerAccountId: 42,
      scopes: ["game:read", "player.online:read"],
      expiresAt: "2027-01-01T00:00:00Z",
    }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ...input, id: 1, token: "twk_x_secret" }, { status: 201 }))
    vi.stubGlobal("fetch", fetchMock)

    await capabilityApi.issueKey("manager", input)

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/auth/keys", expect.objectContaining({
      method: "POST",
      body: JSON.stringify(input),
    }))
  })

  it("正确处理 204 生命周期操作", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })))

    await expect(capabilityApi.disableKey("manager", "abcdef123456")).resolves.toBeUndefined()
  })

  it("updates credential scopes without sending a new secret", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ keyPrefix: "abcdef123456", scopes: ["player.online:read"] }))
    vi.stubGlobal("fetch", fetchMock)

    await capabilityApi.updateKeyScopes("manager", "abcdef123456", ["player.online:read"])

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/auth/keys/abcdef123456/scopes", expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({ scopes: ["player.online:read"] }),
    }))
  })

  it("把鉴权错误码转换为可读信息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(
      { code: "unauthenticated" },
      { status: 401, statusText: "Unauthorized" },
    )))

    await expect(capabilityApi.keys("bad-key")).rejects.toMatchObject({
      message: "管理密钥无效或已经过期。",
      status: 401,
    })
  })

  it("按只读 profile 和搜索词读取凭据可见的能力目录", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      contractVersion: "1",
      catalogVersion: "catalog_0.3.0",
      permissionVersion: "v2",
      tools: [],
      generatedAt: "2026-08-21T00:00:00Z",
    }))
    vi.stubGlobal("fetch", fetchMock)

    await capabilityApi.capabilities("reader", "player inventory")

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/capabilities?profile=read-only&query=player%20inventory",
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer reader" }) }),
    )
  })
})

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  })
}
