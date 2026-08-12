import { afterEach, describe, expect, it, vi } from "vitest"

import { adminApi, ApiError } from "@/api/admin"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("adminApi", () => {
  it("读取并解析管理接口 JSON", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      version: 3,
      configs: { "game.exp.rate": "2.5" },
    }))
    vi.stubGlobal("fetch", fetchMock)

    await expect(adminApi.config()).resolves.toEqual({
      version: 3,
      configs: { "game.exp.rate": "2.5" },
    })
    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/config", expect.objectContaining({
      headers: expect.objectContaining({ Accept: "application/json" }),
    }))
  })

  it("按 JSON 契约提交配置热改", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      key: "game.exp.rate",
      value: "3.0",
      version: 4,
    }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.setConfig("game.exp.rate", "3.0")

    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/config", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ key: "game.exp.rate", value: "3.0" }),
      headers: expect.objectContaining({ "Content-Type": "application/json" }),
    }))
  })

  it("把后端错误码转换为可读信息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(
      { error: "character_not_online" },
      { status: 404, statusText: "Not Found" },
    )))

    await expect(adminApi.kick(888)).rejects.toMatchObject({
      name: "ApiError",
      message: "该角色当前不在线或会话已经断开。",
      status: 404,
    })
  })

  it("明确报告网络不可达", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("fetch failed")))

    await expect(adminApi.health()).rejects.toEqual(
      new ApiError("无法连接管理接口，请确认后端已在 8080 端口启动。"),
    )
  })

  it("拒绝成功状态下的非 JSON 响应", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("ok", {
      status: 200,
      headers: { "Content-Type": "text/plain" },
    })))

    await expect(adminApi.health()).rejects.toMatchObject({
      message: "管理接口没有返回 JSON 数据。",
      status: 200,
    })
  })
})

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  })
}
