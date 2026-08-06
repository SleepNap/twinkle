export type ChannelStatus = "online" | "offline";

export interface ChannelRow {
  id: string;
  name: string;
  status: ChannelStatus;
  online: number;
}

export interface OverviewEvent {
  id: string;
  label: string;
  at: string;
}

export interface OverviewData {
  onlinePlayers: number;
  activeChannels: string;
  systemLoad: number;
  channels: ChannelRow[];
  events: OverviewEvent[];
}
