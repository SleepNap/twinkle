import { Loader2 } from "lucide-react"
import { useState, type ReactNode } from "react"

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
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
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
  requireReason = false,
}: {
  trigger?: ReactNode
  title: string
  description: string
  confirmLabel: string
  destructive?: boolean
  pending?: boolean
  open?: boolean
  onOpenChange?: (open: boolean) => void
  onConfirm: (reason: string) => void
  requireReason?: boolean
}) {
  const { t } = useI18n()
  const [reason, setReason] = useState("")
  const canConfirm = !requireReason || reason.trim().length > 0
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
        {requireReason && (
          <div className="grid gap-2">
            <Label htmlFor="confirm-reason">{t("auth.reasonLabel")}</Label>
            <Input
              id="confirm-reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              placeholder={t("auth.reasonPlaceholder")}
            />
          </div>
        )}
        <DialogFooter>
          {!pending && (
            <DialogClose asChild>
              <Button variant="outline">{t("common.cancel")}</Button>
            </DialogClose>
          )}
          <Button
            variant={destructive ? "destructive" : "default"}
            onClick={() => onConfirm(reason.trim())}
            disabled={pending || !canConfirm}
          >
            {pending && <Loader2 data-icon="inline-start" className="animate-spin" />}
            {pending ? t("common.processing") : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
