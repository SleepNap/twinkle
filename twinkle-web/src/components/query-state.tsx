import { AlertCircle, Inbox, RefreshCw } from "lucide-react"

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { useI18n } from "@/i18n"

export function QueryError({ error, retry }: { error: Error; retry: () => void }) {
  const { t } = useI18n()
  return (
    <Alert variant="destructive">
      <AlertCircle />
      <AlertTitle>{t("query.failed")}</AlertTitle>
      <AlertDescription className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between">
        <span>{error.message}</span>
        <Button variant="outline" size="sm" onClick={retry}>
          <RefreshCw data-icon="inline-start" />
          {t("common.retry")}
        </Button>
      </AlertDescription>
    </Alert>
  )
}

export function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex min-h-48 flex-col items-center justify-center rounded-lg border border-dashed p-8 text-center">
      <Inbox className="mb-3 size-8 text-muted-foreground" />
      <p className="font-medium">{title}</p>
      <p className="mt-1 max-w-md text-sm text-muted-foreground">{description}</p>
    </div>
  )
}
