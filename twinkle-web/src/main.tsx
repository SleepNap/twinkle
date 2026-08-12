import { StrictMode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { createRoot } from "react-dom/client"
import { BrowserRouter } from "react-router-dom"
import { ThemeProvider } from "next-themes"

import App from "./App"
import { Toaster } from "@/components/ui/sonner"
import { I18nProvider } from "@/i18n"
import { CredentialProvider } from "@/auth/credential-provider"
import "./index.css"

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <I18nProvider>
        <CredentialProvider>
          <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
            <BrowserRouter>
              <App />
              <Toaster richColors position="top-right" />
            </BrowserRouter>
          </ThemeProvider>
        </CredentialProvider>
      </I18nProvider>
    </QueryClientProvider>
  </StrictMode>,
)
