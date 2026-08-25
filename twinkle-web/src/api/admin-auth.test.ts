import { afterEach, describe, expect, it, vi } from "vitest"

import { adminAuthApi } from "@/api/admin-auth"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("adminAuthApi", () => {
  it("按登录契约提交管理员账号和密码", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      token: "session-token",
      accountName: "admin",
      permissions: ["console:read", "console:write"],
    }))
    vi.stubGlobal("fetch", fetchMock)

    await expect(adminAuthApi.login("admin", "secret-123")).resolves.toEqual({
      token: "session-token",
      accountName: "admin",
      permissions: ["console:read", "console:write"],
    })
    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ name: "admin", password: "secret-123" }),
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
    })
  })

  it("退出时使用当前会话 Bearer token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ loggedOut: true }))
    vi.stubGlobal("fetch", fetchMock)

    await expect(adminAuthApi.logout("session-token")).resolves.toEqual({ loggedOut: true })
    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/auth/logout", {
      method: "POST",
      headers: {
        Accept: "application/json",
        Authorization: "Bearer session-token",
      },
    })
  })

  it("把 401 登录失败转换为用户可读提示", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(
      { error: "invalid_credentials" },
      { status: 401, statusText: "Unauthorized" },
    )))

    await expect(adminAuthApi.login("admin", "wrong-password")).rejects.toThrow(
      "账号或密码错误，或该账号不是管理员。",
    )
  })

  it("优先展示非 401 JSON 错误信息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(
      { error: "authentication service unavailable" },
      { status: 503, statusText: "Service Unavailable" },
    )))

    await expect(adminAuthApi.login("admin", "secret-123")).rejects.toThrow(
      "authentication service unavailable",
    )
  })

  it("明确报告网络不可达，同时保留主动取消异常", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("fetch failed"))
      .mockRejectedValueOnce(new DOMException("cancelled", "AbortError"))
    vi.stubGlobal("fetch", fetchMock)

    await expect(adminAuthApi.login("admin", "secret-123")).rejects.toThrow(
      "无法连接管理接口，请确认后端已在 8080 端口启动。",
    )
    await expect(adminAuthApi.login("admin", "secret-123")).rejects.toMatchObject({
      name: "AbortError",
    })
  })
})

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  })
}
