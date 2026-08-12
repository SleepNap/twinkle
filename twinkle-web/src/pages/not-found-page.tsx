import { ArrowLeft } from "lucide-react"
import { Link } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { useI18n } from "@/i18n"

export function NotFoundPage() {
  const { t } = useI18n()
  return (
    <div className="flex min-h-[60svh] flex-col items-center justify-center text-center">
      <p className="text-sm font-medium text-muted-foreground">404</p>
      <h1 className="mt-2 text-2xl font-semibold tracking-tight">{t("notFound.title")}</h1>
      <p className="mt-2 text-sm text-muted-foreground">{t("notFound.description")}</p>
      <Button asChild className="mt-5">
        <Link to="/"><ArrowLeft data-icon="inline-start" />{t("notFound.back")}</Link>
      </Button>
    </div>
  )
}
