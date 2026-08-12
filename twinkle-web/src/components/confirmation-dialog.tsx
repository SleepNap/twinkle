import { Loader2 } from "lucide-react"
import type { ReactNode } from "react"

import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { useI18n } from "@/i18n"

export function ConfirmationDialog({
  trigger,
  title,
  description,
  confirmLabel,
  destructive = false,
  pending = false,
  open,
  onOpenChange,
  onConfirm,
}: {
  trigger?: ReactNode
  title: string
  description: string
  confirmLabel: string
  destructive?: boolean
  pending?: boolean
  open?: boolean
  onOpenChange?: (open: boolean) => void
  onConfirm: () => void
}) {
  const { t } = useI18n()
  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!pending || nextOpen) onOpenChange?.(nextOpen)
      }}
    >
      {trigger && <DialogTrigger asChild>{trigger}</DialogTrigger>}
      <DialogContent showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          {!pending && (
            <DialogClose asChild>
              <Button variant="outline">{t("common.cancel")}</Button>
            </DialogClose>
          )}
          <Button
            variant={destructive ? "destructive" : "default"}
            onClick={onConfirm}
            disabled={pending}
          >
            {pending && <Loader2 data-icon="inline-start" className="animate-spin" />}
            {pending ? t("common.processing") : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
