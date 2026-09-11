"use client";
import { useState } from "react";
import { BookOpen, ChartNoAxesCombined, FileText, Languages, Layers3, MessageSquare, PencilLine, Sparkles, Target } from "lucide-react";
import type { Notebook, Source } from "@/shared/api/types";
import { useResource } from "@/shared/api/hooks";
import { SourcePanel } from "@/features/sources/source-panel";
import { ChatPanel } from "@/features/chat/chat-panel";
import { NotesPanel } from "@/features/notes/notes-panel";
import { StudioPanel } from "@/features/quiz/studio-panel";
import { ReviewPanel } from "@/features/review/review-panel";
import { LearningPanel } from "@/features/learning/learning-panel";
import { LanguagePanel } from "@/features/language-lab/language-panel";
import { AnalyticsPanel } from "@/features/learning/analytics-panel";
import type { StreamCitation } from "@/shared/ws/protocol";
import { ErrorNotice, Modal } from "@/shared/ui/primitives";
import { useAction } from "@/shared/api/hooks";
import { useAuth } from "@/features/auth/provider";

const tabs = [{ id: "chat", label: "Hỏi tài liệu", icon: MessageSquare }, { id: "notes", label: "Ghi chú", icon: PencilLine }, { id: "studio", label: "Góc luyện tập", icon: Sparkles }, { id: "review", label: "Thẻ ghi nhớ", icon: Layers3 }, { id: "learning", label: "Kiến thức", icon: Target }, { id: "language", label: "Ngôn ngữ", icon: Languages }, { id: "analytics", label: "Tiến độ", icon: ChartNoAxesCombined }] as const;
export function NotebookWorkspace({ notebook, onChanged }: { notebook: Notebook; onChanged: (notebook: Notebook) => void }) {
  const { api } = useAuth();
  const [tab, setTab] = useState<(typeof tabs)[number]["id"]>("chat");
  const [sourceVisible, setSourceVisible] = useState(false);
  const [selectedSources, setSelectedSources] = useState<string[]>([]);
  const [citation, setCitation] = useState<StreamCitation | null>(null);
  const [edit, setEdit] = useState(false);
  const path = `/api/v1/notebooks/${notebook.id}/sources`;
  const sources = useResource<Source[]>(path, data => data?.some(source => !["READY", "FAILED", "DELETED"].includes(source.status)) ? 2500 : false);
  const save = useAction((body: { title: string; description: string; goalText: string }) => api.patch<Notebook>(`/api/v1/notebooks/${notebook.id}`, body), [`/api/v1/workspaces/${notebook.workspaceId}/notebooks`]);
  function navigateCitation(value: StreamCitation) { setCitation(value); setSourceVisible(true); }
  return <><div className="notebook-heading"><div className="row"><span className="notebook-title-icon"><BookOpen size={23} /></span><div><h1>{notebook.title}</h1><p className="muted">{notebook.goalText || notebook.description || "Thêm tài liệu, đặt câu hỏi và xây dựng kiến thức của bạn."}</p></div></div><button className="icon-button" aria-label="Chỉnh sửa sổ tay" onClick={() => setEdit(true)}><PencilLine size={18} /></button></div><div className="notebook-tabs" role="tablist" aria-label="Công cụ sổ tay">{tabs.map(item => <button key={item.id} role="tab" id={`tab-${item.id}`} aria-controls="notebook-content" aria-selected={tab === item.id} onClick={() => setTab(item.id)}><item.icon size={17} />{item.label}</button>)}</div><div className={`notebook-layout ${sourceVisible ? "sources-visible" : ""}`}><aside className="sources-column"><SourcePanel notebookId={notebook.id} sources={sources.data || []} loading={sources.isPending} error={sources.error} refetch={() => { void sources.refetch(); }} selected={selectedSources} onSelection={setSelectedSources} citation={citation} onClearCitation={() => setCitation(null)} onClose={() => setSourceVisible(false)} /></aside><section className="notebook-content" id="notebook-content" role="tabpanel" aria-labelledby={`tab-${tab}`}><button className="button show-sources" onClick={() => setSourceVisible(true)}><FileText size={16} />Tài liệu nguồn ({sources.data?.length || 0})</button>{tab === "chat" && <ChatPanel notebookId={notebook.id} sourceIds={selectedSources.filter(id => sources.data?.some(source => source.id === id && source.status === "READY"))} sources={sources.data || []} onCitation={navigateCitation} />}{tab === "notes" && <NotesPanel notebookId={notebook.id} />}{tab === "studio" && <StudioPanel notebookId={notebook.id} sourceIds={selectedSources} onReview={() => setTab("review")} />}{tab === "review" && <ReviewPanel notebookId={notebook.id} onCitation={navigateCitation} />}{tab === "learning" && <LearningPanel notebookId={notebook.id} workspaceId={notebook.workspaceId} onAction={action => setTab(action === "REVIEW_FLASHCARDS" ? "review" : action === "RETRY_QUIZ" ? "studio" : "chat")} />}{tab === "language" && <LanguagePanel notebookId={notebook.id} sources={sources.data || []} />}{tab === "analytics" && <AnalyticsPanel notebookId={notebook.id} />}</section></div>{edit && <Modal title="Chỉnh sửa sổ tay" onClose={() => { if (!save.isPending) setEdit(false); }}><form className="stack" onSubmit={event => { event.preventDefault(); const data = new FormData(event.currentTarget); save.mutate({ title: String(data.get("title")).trim(), description: String(data.get("description")).trim(), goalText: String(data.get("goalText")).trim() }, { onSuccess: value => { onChanged(value); setEdit(false); } }); }}><label>Tên sổ tay<input name="title" defaultValue={notebook.title} maxLength={200} required /></label><label>Mô tả<textarea name="description" defaultValue={notebook.description || ""} /></label><label>Mục tiêu<textarea name="goalText" defaultValue={notebook.goalText || ""} /></label><ErrorNotice error={save.error} /><button className="button primary" disabled={save.isPending}>Lưu thay đổi</button></form></Modal>}</>;
}
