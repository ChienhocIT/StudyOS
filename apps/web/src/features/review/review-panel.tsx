"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowRight,
  BookOpen,
  Check,
  Layers3,
  RotateCcw,
  Sparkles,
} from "lucide-react";
import { useAuth } from "@/features/auth/provider";
import { useAction, useResource } from "@/shared/api/hooks";
import type { ReviewItem } from "@/shared/api/types";
import type { StreamCitation } from "@/shared/ws/protocol";
import { Empty, ErrorNotice, Loading } from "@/shared/ui/primitives";

const grades = [
  { value: 1, label: "Chưa nhớ", hint: "Ôn lại sớm" },
  { value: 2, label: "Khó", hint: "Còn do dự" },
  { value: 3, label: "Đã nhớ", hint: "Trả lời tốt" },
  { value: 4, label: "Rất dễ", hint: "Nắm chắc" },
];

export function ReviewPanel({
  notebookId,
  onCitation,
}: {
  notebookId?: string;
  onCitation?: (citation: StreamCitation) => void;
}) {
  const { api } = useAuth();
  const queuePath = `/api/v1/review/queue?limit=50${notebookId ? `&notebookId=${notebookId}` : ""}`;
  const queue = useResource<ReviewItem[]>(queuePath);
  const [session, setSession] = useState<ReviewItem[] | null>(null);
  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [reviewed, setReviewed] = useState(0);
  const current = session?.[index];
  const reviewRequest = useRef<{ cardId: string; key: string; reviewedAt: string; grade: number } | null>(null);
  const submitting = useRef(false);
  const review = useAction(
    (input: { card: ReviewItem; grade: number }) => {
      if (reviewRequest.current?.cardId !== input.card.cardId) reviewRequest.current = { cardId: input.card.cardId, key: crypto.randomUUID(), reviewedAt: new Date().toISOString(), grade: input.grade };
      return api.post(
        `/api/v1/flashcards/${input.card.cardId}/reviews`,
        {
          grade: reviewRequest.current.grade,
          reviewedAt: reviewRequest.current.reviewedAt,
        },
        { headers: { "Idempotency-Key": reviewRequest.current.key } },
      );
    },
    [queuePath, "/api/v1/analytics/overview", ...(notebookId ? [`/api/v1/notebooks/${notebookId}/mastery`, `/api/v1/analytics/notebooks/${notebookId}`] : [])],
  );

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (!session || !current || review.isPending || (event.target instanceof HTMLElement && (event.target.matches("input, textarea, select, button, a") || event.target.isContentEditable))) return;
      if (event.code === "Space") {
        event.preventDefault();
        setRevealed(true);
      }
      const grade = Number(event.key);
      if (revealed && grade >= 1 && grade <= 4) submitGrade(grade);
    };
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  });

  const remaining = useMemo(
    () =>
      session ? Math.max(0, session.length - index) : queue.data?.length || 0,
    [session, queue.data?.length, index],
  );

  function submitGrade(grade: number) {
    if (!current || submitting.current) return;
    submitting.current = true;
    review.mutate(
      { card: current, grade },
      {
        onSuccess: () => {
          setReviewed((value) => value + 1);
          setIndex((value) => value + 1);
          setRevealed(false);
        },
        onSettled: () => { submitting.current = false; },
      },
    );
  }

  if (queue.isPending) return <Loading label="Đang chuẩn bị thẻ ôn tập…" />;
  if (queue.error)
    return (
      <ErrorNotice
        error={queue.error}
        retry={() => {
          void queue.refetch();
        }}
      />
    );
  if (!session)
    return (
      <div className="feature-panel review-overview">
        <div className="review-hero">
          <div>
            <span className="review-hero-icon">
              <Layers3 size={28} />
            </span>
            <h2>
              {remaining
                ? `${remaining} thẻ chờ bạn hôm nay`
                : "Hôm nay bạn đã ôn xong"}
            </h2>
            <p>
              {remaining
                ? "Một phiên ngắn giúp kiến thức quay lại đúng lúc."
                : "Quay lại vào phiên tiếp theo khi có thẻ đến hạn."}
            </p>
          </div>
          {remaining > 0 && (
            <button
              className="button primary"
              onClick={() => {
                setSession(queue.data || []);
                setIndex(0);
                setReviewed(0);
              }}
            >
              Bắt đầu ôn
              <ArrowRight size={16} />
            </button>
          )}
        </div>
        {remaining === 0 && (
          <Empty title="Không có thẻ đến hạn">
            <p>Bạn có thể tạo thêm flashcard từ Góc luyện tập.</p>
          </Empty>
        )}
        {remaining > 0 && (
          <div className="review-session-note">
            <Sparkles size={18} />
            <p>
              Đọc câu hỏi, tự trả lời, sau đó lật thẻ và đánh giá mức độ nhớ
              thật của bạn.
            </p>
          </div>
        )}
      </div>
    );
  if (!current)
    return (
      <div className="review-complete">
        <span>
          <Check size={30} />
        </span>
        <h2>Hoàn thành phiên ôn tập</h2>
        <p>Bạn đã ôn {reviewed} thẻ. Lịch ôn tiếp theo đã được cập nhật.</p>
        <button
          className="button primary"
          onClick={() => {
            setSession(null);
            void queue.refetch();
          }}
        >
          Kết thúc phiên
        </button>
      </div>
    );

  return (
    <div className="review-session">
      <div className="review-session-head">
        <button className="text-button" onClick={() => setSession(null)}>
          <RotateCcw size={15} />
          Kết thúc sớm
        </button>
        <span>
          {index + 1} / {session.length}
        </span>
      </div>
      <button
        className={`flashcard ${revealed ? "revealed" : ""}`}
        onClick={() => setRevealed(true)}
        aria-label={revealed ? "Thẻ đã lật" : "Lật thẻ để xem đáp án"}
      >
        <span>{revealed ? "Đáp án" : "Câu hỏi"}</span>
        <strong>{revealed ? current.back : current.front}</strong>
        {!revealed && <small>Nhấn để xem đáp án</small>}
      </button>
      {current.sourceCitation && onCitation && (
        <button
          className="text-button citation-link"
          onClick={() =>
            onCitation({ ...current.sourceCitation!, provisional: false })
          }
        >
          <BookOpen size={14} />
          Xem nguồn
        </button>
      )}
      <ErrorNotice error={review.error} />
      {revealed ? (
        <div className="review-grades" aria-label="Đánh giá mức độ ghi nhớ">
          {grades.map((grade) => (
            <button
              key={grade.value}
              disabled={review.isPending}
              onClick={() => submitGrade(grade.value)}
            >
              <kbd>{grade.value}</kbd>
              <strong>{grade.label}</strong>
              <small>{grade.hint}</small>
            </button>
          ))}
        </div>
      ) : (
        <button
          className="button primary reveal-button"
          onClick={() => setRevealed(true)}
        >
          Hiện đáp án
        </button>
      )}
    </div>
  );
}
