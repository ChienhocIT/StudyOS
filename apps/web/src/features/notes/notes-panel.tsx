"use client";
import { useState } from "react";
import ReactMarkdown from "react-markdown";
import { PencilLine, Plus, Trash2 } from "lucide-react";
import { useAuth } from "@/features/auth/provider";
import { useAction, useResource } from "@/shared/api/hooks";
import type { Note } from "@/shared/api/types";
import {
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  formatDate,
} from "@/shared/ui/primitives";
export function NotesPanel({ notebookId }: { notebookId: string }) {
  const { api } = useAuth();
  const path = `/api/v1/notebooks/${notebookId}/notes`;
  const notes = useResource<Note[]>(path);
  const [editing, setEditing] = useState<Note | "new" | null>(null);
  const [deleting, setDeleting] = useState<Note | null>(null);
  const save = useAction(
    (body: { title: string; content: string }) =>
      editing === "new"
        ? api.post<Note>(path, body)
        : api.patch<Note>(`/api/v1/notes/${editing && editing.id}`, body),
    [path],
  );
  const remove = useAction(
    (id: string) => api.delete(`/api/v1/notes/${id}`),
    [path],
  );
  return (
    <div className="feature-panel">
      <div className="section-heading">
        <div>
          <span className="eyebrow">VIẾT ĐỂ HIỂU</span>
          <h2>Ghi chú của bạn</h2>
          <p className="muted">
            Lưu ý tưởng, kết nối và lời giải thích bằng cách của bạn.
          </p>
        </div>
        <button
          className="button primary"
          onClick={() => {
            save.reset();
            setEditing("new");
          }}
        >
          <Plus size={16} />
          Ghi chú mới
        </button>
      </div>
      <ErrorNotice
        error={notes.error}
        retry={() => {
          void notes.refetch();
        }}
      />
      {notes.isPending ? (
        <Loading />
      ) : !notes.data?.length ? (
        <Empty title="Ý tưởng hay bắt đầu từ một dòng ghi chú">
          <p>Viết ghi chú mới hoặc lưu câu trả lời từ hội thoại.</p>
        </Empty>
      ) : (
        <div className="notes-grid">
          {notes.data.map((note) => (
            <article key={note.id} className="note-card">
              <div className="between">
                <span className="note-date">
                  {formatDate(note.updatedAt || note.createdAt)}
                </span>
                <div className="row">
                  <button
                    className="icon-button"
                    aria-label={`Sửa ${note.title}`}
                    onClick={() => {
                      save.reset();
                      setEditing(note);
                    }}
                  >
                    <PencilLine size={15} />
                  </button>
                  <button
                    className="icon-button"
                    aria-label={`Xóa ${note.title}`}
                    onClick={() => {
                      remove.reset();
                      setDeleting(note);
                    }}
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
              </div>
              <h3>{note.title}</h3>
              <div className="markdown">
                <ReactMarkdown>{note.content}</ReactMarkdown>
              </div>
              {Boolean(note.citationIds?.length) && (
                <small className="muted">
                  {note.citationIds?.length} trích dẫn được lưu cùng ghi chú
                </small>
              )}
            </article>
          ))}
        </div>
      )}
      {editing && (
        <Modal
          title={editing === "new" ? "Ghi chú mới" : "Chỉnh sửa ghi chú"}
          onClose={() => {
            if (!save.isPending) setEditing(null);
          }}
        >
          <form
            className="stack"
            onSubmit={(event) => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              save.mutate(
                {
                  title: String(data.get("title")).trim(),
                  content: String(data.get("content")),
                },
                { onSuccess: () => setEditing(null) },
              );
            }}
          >
            <label>
              Tiêu đề
              <input
                name="title"
                required
                maxLength={200}
                defaultValue={editing === "new" ? "" : editing.title}
                autoFocus
              />
            </label>
            <label>
              Nội dung <span className="muted">(hỗ trợ Markdown)</span>
              <textarea
                name="content"
                required
                rows={12}
                defaultValue={editing === "new" ? "" : editing.content}
              />
            </label>
            <ErrorNotice error={save.error} />
            <button className="button primary" disabled={save.isPending}>
              {save.isPending ? "Đang lưu…" : "Lưu ghi chú"}
            </button>
          </form>
        </Modal>
      )}
      {deleting && (
        <Modal title="Xóa ghi chú?" onClose={() => setDeleting(null)}>
          <p>Ghi chú “{deleting.title}” sẽ bị xóa.</p>
          <ErrorNotice error={remove.error} />
          <div className="modal-actions">
            <button className="button" onClick={() => setDeleting(null)}>
              Giữ ghi chú
            </button>
            <button
              className="button danger"
              disabled={remove.isPending}
              onClick={() =>
                remove.mutate(deleting.id, {
                  onSuccess: () => setDeleting(null),
                })
              }
            >
              Xóa ghi chú
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
