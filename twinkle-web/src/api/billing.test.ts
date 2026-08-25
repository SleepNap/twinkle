import { afterEach, describe, expect, it, vi } from "vitest"

import { billingApi } from "@/api/billing"

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("billingApi", () => {
  it("后端省略空的账号额度集合时归一化为空数组", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({})))

    await expect(billingApi.accounts()).resolves.toEqual({ accounts: [] })
  })

  it("后端省略空的积分流水集合时归一化为空数组", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}))
    vi.stubGlobal("fetch", fetchMock)

    await expect(billingApi.transactions(7)).resolves.toEqual({ transactions: [] })
    expect(fetchMock).toHaveBeenCalledWith(
      "/admin/v1/billing/transactions?accountId=7",
      expect.objectContaining({ headers: expect.objectContaining({ Accept: "application/json" }) }),
    )
  })
})

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  })
}
