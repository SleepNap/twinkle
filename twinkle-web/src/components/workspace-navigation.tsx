import { type ComponentType, type LazyExoticComponent } from "react"
import {
  Activity,
  BookOpen,
  Coins,
  FileJson,
  FileSearch,
  KeyRound,
  ListTodo,
  Radio,
  Settings2,
  ShieldCheck,
  UserRoundSearch,
  Users,
  Wrench,
  type LucideIcon,
} from "lucide-react"

import type { MessageKey } from "@/i18n"
import {
  AccountsPage,
  ApiKeysPage,
  AuditsPage,
  BillingPage,
  CapabilitiesPage,
  ChannelsPage,
  ConfigPage,
  OperationsPage,
  OverviewPage,
  PlayersPage,
  RolesPage,
  TasksPage,
} from "@/components/workspace-pages"

export interface WorkspaceRouteDefinition {
  path: string
  label: MessageKey
  icon: LucideIcon
  component: LazyExoticComponent<ComponentType>
}

export interface NavigationGroup {
  key: string
  label: MessageKey
  routePaths: string[]
  externalItems?: Array<{
    href: string
    label: MessageKey
    icon: LucideIcon
  }>
}

export const workspaceRoutes: WorkspaceRouteDefinition[] = [
  { path: "/", label: "nav.overview", icon: Activity, component: OverviewPage },
  { path: "/channels", label: "nav.channels", icon: Radio, component: ChannelsPage },
  { path: "/players", label: "nav.players", icon: Users, component: PlayersPage },
  { path: "/tasks", label: "nav.tasks", icon: ListTodo, component: TasksPage },
  { path: "/accounts", label: "nav.accounts", icon: UserRoundSearch, component: AccountsPage },
  { path: "/billing", label: "nav.billing", icon: Coins, component: BillingPage },
  { path: "/config", label: "nav.config", icon: Settings2, component: ConfigPage },
  { path: "/operations", label: "nav.operations", icon: Wrench, component: OperationsPage },
  { path: "/roles", label: "nav.roles", icon: ShieldCheck, component: RolesPage },
  { path: "/api-keys", label: "nav.apiKeys", icon: KeyRound, component: ApiKeysPage },
  { path: "/audits", label: "nav.audits", icon: FileSearch, component: AuditsPage },
  { path: "/capabilities", label: "nav.capabilities", icon: BookOpen, component: CapabilitiesPage },
]

export const workspaceRoutesByPath = new Map(workspaceRoutes.map((route) => [route.path, route]))

export const primaryNavigationPaths = ["/"]

export const navigationGroups: NavigationGroup[] = [
  {
    key: "runtime-monitoring",
    label: "nav.group.runtimeMonitoring",
    routePaths: ["/channels", "/tasks"],
  },
  {
    key: "player-business",
    label: "nav.group.playerBusiness",
    routePaths: ["/accounts", "/players"],
  },
  {
    key: "system-operations",
    label: "nav.group.systemOperations",
    routePaths: ["/config", "/operations"],
  },
  {
    key: "security",
    label: "nav.group.securityAudit",
    routePaths: ["/roles", "/api-keys", "/billing", "/audits"],
  },
  {
    key: "developer",
    label: "nav.group.developer",
    routePaths: ["/capabilities"],
    externalItems: [{ href: "/docs/index.html", label: "nav.apiDocs", icon: FileJson }],
  },
]
