import type { OverviewData } from "./types";

const API_BASE = import.meta.env.VITE_API_BASE ?? "/api/v1";

// Mock 数据：后端（M3/M5）就绪后，将 USE_MOCK 置为 false 即切真实请求。
const MOCK: OverviewData = {
  onlinePlayers: 1284,
  activeChannels: "12 / 12",
  systemLoad: 38,
  channels: [
    { id: "m1", name: "大陆一", status: "online", online: 312 },
    { id: "m2", name: "大陆二", status: "online", online: 298 },
    { id: "m3", name: "冒险岛", status: "online", online: 421 },
    { id: "m4", name: "武陵", status: "offline", online: 0 },
  ],
  events: [
    { id: "e1", label: "玩家登入", at: "刚刚" },
    { id: "e2", label: "配置广播", at: "1 分钟前" },
    { id: "e3", label: "心跳正常", at: "2 分钟前" },
    { id: "e4", label: "封禁生效", at: "5 分钟前" },
    { id: "e5", label: "AI 任务完成", at: "8 分钟前" },
  ],
};

const USE_MOCK = true;

export async function getOverview(): Promise<OverviewData> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 300));
    return MOCK;
  }
  const res = await fetch(`${API_BASE}/overview`, { credentials: "include" });
  if (!res.ok) throw new Error(`overview ${res.status}`);
  return (await res.json()) as OverviewData;
}
