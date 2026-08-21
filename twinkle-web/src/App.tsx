import { type ReactElement } from "react"
import { Navigate, Route, Routes } from "react-router-dom"

import { useAdminAuth } from "@/auth/use-admin-auth"
import { AppShell } from "@/components/app-shell"
import { LoginPage } from "@/pages/login-page"

function RequireAuth({ children }: { children: ReactElement }) {
  const { token } = useAdminAuth()
  if (!token) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/*" element={<RequireAuth><AppShell /></RequireAuth>} />
    </Routes>
  )
}
