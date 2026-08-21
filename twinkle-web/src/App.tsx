import { lazy, type ReactElement } from "react"
import { Navigate, Route, Routes } from "react-router-dom"

import { useAdminAuth } from "@/auth/use-admin-auth"
import { AppShell } from "@/components/app-shell"
import { LoginPage } from "@/pages/login-page"

const OverviewPage = lazy(() => import("@/pages/overview-page").then((module) => ({ default: module.OverviewPage })))
const ChannelsPage = lazy(() => import("@/pages/channels-page").then((module) => ({ default: module.ChannelsPage })))
const PlayersPage = lazy(() => import("@/pages/players-page").then((module) => ({ default: module.PlayersPage })))
const AccountsPage = lazy(() => import("@/pages/accounts-page").then((module) => ({ default: module.AccountsPage })))
const BillingPage = lazy(() => import("@/pages/billing-page").then((module) => ({ default: module.BillingPage })))
const ConfigPage = lazy(() => import("@/pages/config-page").then((module) => ({ default: module.ConfigPage })))
const OperationsPage = lazy(() => import("@/pages/operations-page").then((module) => ({ default: module.OperationsPage })))
const ApiKeysPage = lazy(() => import("@/pages/api-keys-page").then((module) => ({ default: module.ApiKeysPage })))
const AuditsPage = lazy(() => import("@/pages/audits-page").then((module) => ({ default: module.AuditsPage })))
const RolesPage = lazy(() => import("@/pages/roles-page").then((module) => ({ default: module.RolesPage })))
const TasksPage = lazy(() => import("@/pages/tasks-page").then((module) => ({ default: module.TasksPage })))
const CapabilitiesPage = lazy(() => import("@/pages/capabilities-page").then((module) => ({ default: module.CapabilitiesPage })))
const NotFoundPage = lazy(() => import("@/pages/not-found-page").then((module) => ({ default: module.NotFoundPage })))

function RequireAuth({ children }: { children: ReactElement }) {
  const { token } = useAdminAuth()
  if (!token) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth><AppShell /></RequireAuth>}>
        <Route index element={<OverviewPage />} />
        <Route path="channels" element={<ChannelsPage />} />
        <Route path="players" element={<PlayersPage />} />
        <Route path="accounts" element={<AccountsPage />} />
        <Route path="billing" element={<BillingPage />} />
        <Route path="config" element={<ConfigPage />} />
        <Route path="operations" element={<OperationsPage />} />
        <Route path="api-keys" element={<ApiKeysPage />} />
        <Route path="audits" element={<AuditsPage />} />
        <Route path="roles" element={<RolesPage />} />
        <Route path="tasks" element={<TasksPage />} />
        <Route path="capabilities" element={<CapabilitiesPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
