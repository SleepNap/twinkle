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
import { Switch } from "@/components/ui/switch"
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
  confirmationText,
  forceOption,
}: {
  trigger?: ReactNode
  title: string
  description: string
  confirmLabel: string
  destructive?: boolean
  pending?: boolean
  open?: boolean
  onOpenChange?: (open: boolean) => void
  onConfirm: (reason: string, force: boolean) => void
  requireReason?: boolean
  confirmationText?: { label: string; expected: string }
  forceOption?: { label: string; description: string }
}) {
  const { t } = useI18n()
  const [reason, setReason] = useState("")
  const [confirmation, setConfirmation] = useState("")
  const [force, setForce] = useState(false)
  const canConfirm = (!requireReason || reason.trim().length > 0)
    && (!confirmationText || confirmation === confirmationText.expected)
  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!pending || nextOpen) {
          if (!nextOpen) {
            setReason("")
            setConfirmation("")
            setForce(false)
          }
          onOpenChange?.(nextOpen)
        }
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
        {confirmationText && (
          <div className="grid gap-2">
            <Label htmlFor="confirm-text">{confirmationText.label}</Label>
            <Input
              id="confirm-text"
              value={confirmation}
              onChange={(event) => setConfirmation(event.target.value)}
              autoComplete="off"
            />
          </div>
        )}
        {forceOption && (
          <div className="flex items-start justify-between gap-4 rounded-md border border-destructive/30 bg-destructive/5 p-3">
            <div className="grid gap-1">
              <Label htmlFor="confirm-force">{forceOption.label}</Label>
              <p className="text-xs text-muted-foreground">{forceOption.description}</p>
            </div>
            <Switch
              id="confirm-force"
              checked={force}
              onCheckedChange={setForce}
              disabled={pending}
              aria-label={forceOption.label}
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
            onClick={() => onConfirm(reason.trim(), force)}
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
