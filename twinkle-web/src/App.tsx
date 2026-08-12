import { lazy } from "react"
import { Route, Routes } from "react-router-dom"

import { AppShell } from "@/components/app-shell"

const OverviewPage = lazy(() => import("@/pages/overview-page").then((module) => ({ default: module.OverviewPage })))
const ChannelsPage = lazy(() => import("@/pages/channels-page").then((module) => ({ default: module.ChannelsPage })))
const PlayersPage = lazy(() => import("@/pages/players-page").then((module) => ({ default: module.PlayersPage })))
const AccountsPage = lazy(() => import("@/pages/accounts-page").then((module) => ({ default: module.AccountsPage })))
const ConfigPage = lazy(() => import("@/pages/config-page").then((module) => ({ default: module.ConfigPage })))
const OperationsPage = lazy(() => import("@/pages/operations-page").then((module) => ({ default: module.OperationsPage })))
const ApiKeysPage = lazy(() => import("@/pages/api-keys-page").then((module) => ({ default: module.ApiKeysPage })))
const AuditsPage = lazy(() => import("@/pages/audits-page").then((module) => ({ default: module.AuditsPage })))
const NotFoundPage = lazy(() => import("@/pages/not-found-page").then((module) => ({ default: module.NotFoundPage })))

export default function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<OverviewPage />} />
        <Route path="channels" element={<ChannelsPage />} />
        <Route path="players" element={<PlayersPage />} />
        <Route path="accounts" element={<AccountsPage />} />
        <Route path="config" element={<ConfigPage />} />
        <Route path="operations" element={<OperationsPage />} />
        <Route path="api-keys" element={<ApiKeysPage />} />
        <Route path="audits" element={<AuditsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
