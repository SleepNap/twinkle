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

    await adminApi.setConfig("game.exp.rate", "3.0", "test")

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

    await expect(adminApi.kick(888, "test")).rejects.toMatchObject({
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
  it("updates a schedule using the task management contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ scheduleId: "daily", enabled: false }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.setScheduleEnabled("daily", false, "test")

    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/schedules/daily/enabled", expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({ enabled: false }),
    }))
  })

  it("带审计原因触发 WZ 热重载", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      version: 2,
      resources: { items: 10, mobs: 20 },
      runtimeObjects: { "channel-maps": 3 },
    }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.reloadWz("更新活动数据")

    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/reload/wz", expect.objectContaining({
      method: "POST",
      headers: expect.objectContaining({ "X-Admin-Reason": "更新活动数据" }),
    }))
  })

  it("生成带审计原因的临时密码", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      generated: true,
      accountId: 7,
      temporaryPassword: "Abcd2345Wxyz",
      expiresAt: "2026-08-21T10:30:00Z",
      oneTime: true,
    }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.generateTemporaryPassword(7, "排查卡图", 30)

    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/accounts/7/temporary-password", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ durationMinutes: 30 }),
      headers: expect.objectContaining({ "X-Admin-Reason": "排查卡图" }),
    }))
  })

  it("创建账号时提交初始密码并携带审计原因", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      id: 19,
      name: "new_player",
      banned: false,
      muted: false,
      loggedIn: false,
    }, { status: 201 }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.createAccount({
      name: "new_player",
      password: "secret-123",
      email: "player@example.com",
      characterSlots: 6,
    }, "新玩家开户")

    expect(fetchMock).toHaveBeenCalledWith("/admin/v1/accounts", expect.objectContaining({
      method: "POST",
      body: JSON.stringify({
        name: "new_player",
        password: "secret-123",
        email: "player@example.com",
        characterSlots: 6,
      }),
      headers: expect.objectContaining({ "X-Admin-Reason": "新玩家开户" }),
    }))
  })

  it("修改和删除账号都携带审计原因", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ id: 19, name: "new_player" }))
      .mockResolvedValueOnce(jsonResponse({
        deleted: true,
        accountId: 19,
        characters: 2,
        relatedRows: 12,
      }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.updateAccount(19, { nick: "新昵称", language: 3 }, "修正资料")
    await adminApi.deleteAccount(19, "测试账号清理")

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/admin/v1/accounts/19", expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({ nick: "新昵称", language: 3 }),
      headers: expect.objectContaining({ "X-Admin-Reason": "修正资料" }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/admin/v1/accounts/19", expect.objectContaining({
      method: "DELETE",
      headers: expect.objectContaining({ "X-Admin-Reason": "测试账号清理" }),
    }))
  })

  it("按角色启停带过滤条件的封包监听", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ configured: true, enabled: true, events: [] }))
      .mockResolvedValueOnce(jsonResponse({ configured: true, enabled: false, events: [] }))
    vi.stubGlobal("fetch", fetchMock)

    await adminApi.startPacketTrace(42, {
      mode: "EXCLUDE",
      directions: ["INBOUND"],
      opcodes: ["MOVE_LIFE", "GENERAL_CHAT"],
      maxPayloadBytes: 4096,
    }, "核查异常攻击")
    await adminApi.stopPacketTrace(42, "证据已收集")

    expect(fetchMock).toHaveBeenNthCalledWith(1, "/admin/v1/packet-traces/42", expect.objectContaining({
      method: "PUT",
      body: JSON.stringify({
        mode: "EXCLUDE",
        directions: ["INBOUND"],
        opcodes: ["MOVE_LIFE", "GENERAL_CHAT"],
        maxPayloadBytes: 4096,
      }),
      headers: expect.objectContaining({ "X-Admin-Reason": "核查异常攻击" }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/admin/v1/packet-traces/42", expect.objectContaining({
      method: "DELETE",
      headers: expect.objectContaining({ "X-Admin-Reason": "证据已收集" }),
    }))
  })
})

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  })
}
