"use client";
import type { ReactNode } from "react";
import { AlertCircle, BookOpen, LoaderCircle, X } from "lucide-react";
import { ApiError } from "@/shared/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";

export function Loading({ label = "Đang tải…" }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <LoaderCircle size={18} className="spin" />
      {label}
    </div>
  );
}
export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="skeleton" role="status" aria-label="Đang tải nội dung">
      {Array.from({ length: lines }, (_, index) => (
        <span key={index} style={{ width: `${92 - index * 11}%` }} />
      ))}
    </div>
  );
}
export function ErrorNotice({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  if (!error) return null;
  return (
    <div className="error-notice" role="alert">
      <AlertCircle size={18} />
      <div>
        <p>
          {error instanceof Error
            ? error.message
            : "Đã xảy ra lỗi. Vui lòng thử lại."}
        </p>
        {error instanceof ApiError && error.traceId && (
          <small>Mã hỗ trợ: {error.traceId}</small>
        )}
        {retry && (
          <Button variant="link" className="text-button" onClick={retry}>
            Thử lại
          </Button>
        )}
      </div>
    </div>
  );
}
export function Empty({
  title,
  children,
  icon,
}: {
  title: string;
  children?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon || <BookOpen size={28} />}</div>
      <h3>{title}</h3>
      <div className="muted">{children}</div>
    </div>
  );
}
export function Modal({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        className="modal"
        showCloseButton={false}
        aria-describedby={undefined}
      >
        <div className="modal-head">
          <DialogTitle>{title}</DialogTitle>
          <Button
            variant="ghost"
            size="icon"
            className="icon-button"
            aria-label="Đóng"
            onClick={onClose}
          >
            <X size={20} />
          </Button>
        </div>
        {children}
      </DialogContent>
    </Dialog>
  );
}
export function Progress({ value, label }: { value: number; label: string }) {
  return (
    <div className="progress-group">
      <div className="between">
        <span>{label}</span>
        <span>{Math.round(value)}%</span>
      </div>
      <progress max={100} value={value} aria-label={label} />
    </div>
  );
}
export function formatDate(value?: string | null) {
  return value
    ? new Intl.DateTimeFormat("vi-VN", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
      }).format(new Date(value))
    : "Chưa có";
}
export function formatTime(ms: number) {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
export function safeExternalUrl(value?: string | null) {
  if (!value) return undefined;
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) ? url.href : undefined;
  } catch {
    return undefined;
  }
}
