"use client";

import { useEffect, useMemo, useState } from "react";
import {
  ArrowRight,
  BookCheck,
  Check,
  ChevronLeft,
  FileQuestion,
  Layers3,
  ListChecks,
  Sparkles,
} from "lucide-react";
import { useAuth } from "@/features/auth/provider";
import { useAction, useResource } from "@/shared/api/hooks";
import type { Job, Quiz, QuizAttempt, QuizQuestion } from "@/shared/api/types";
import { Empty, ErrorNotice, Loading, Progress } from "@/shared/ui/primitives";

type ArtifactKind = "quiz" | "flashcards" | "study-guide";

const artifacts: Array<{
  id: ArtifactKind;
  title: string;
  description: string;
  icon: typeof FileQuestion;
}> = [
  { id: "quiz", title: "Bài kiểm tra", description: "Kiểm tra mức hiểu bằng câu hỏi có chấm điểm.", icon: FileQuestion },
  { id: "flashcards", title: "Bộ thẻ ghi nhớ", description: "Chuyển ý chính thành thẻ để ôn theo lịch.", icon: Layers3 },
  { id: "study-guide", title: "Hướng dẫn học", description: "Tạo lộ trình đọc ngắn gọn từ nguồn đã chọn.", icon: BookCheck },
];

export function StudioPanel({ notebookId, sourceIds, onReview }: { notebookId: string; sourceIds: string[]; onReview: () => void }) {
  const { api } = useAuth();
  const [job, setJob] = useState<Job | null>(null);
  const [kind, setKind] = useState<ArtifactKind | null>(null);
  const [quizId, setQuizId] = useState<string | null>(null);
  const generate = useAction(async (nextKind: ArtifactKind) => {
    const created = await api.post<Job>(`/api/v1/notebooks/${notebookId}/artifacts/${nextKind}`, {
      sourceIds,
      count: nextKind === "quiz" ? 8 : nextKind === "flashcards" ? 12 : 6,
      difficulty: 0.55,
      focusWeakConcepts: true,
    });
    return { created, nextKind };
  });
  const jobState = useResource<Job>(job ? `/api/v1/jobs/${job.id}` : null, current => current && ["PENDING", "RUNNING"].includes(current.status) ? 1400 : false);
  const currentJob = jobState.data || job;

  useEffect(() => {
    if (currentJob?.status === "SUCCEEDED" && kind === "quiz" && currentJob.resultRef) setQuizId(currentJob.resultRef);
  }, [currentJob?.status, currentJob?.resultRef, kind]);

  if (quizId) return <QuizRunner quizId={quizId} onClose={() => { setQuizId(null); setJob(null); setKind(null); }} />;

  return <div className="feature-panel studio-panel">
    <div className="section-heading compact-heading">
      <div>
        <h2>Chọn cách bạn muốn luyện</h2>
        <p className="muted">StudyOS tạo hoạt động từ chính tài liệu đã sẵn sàng.</p>
      </div>
      {sourceIds.length > 0 && <span className="selection-count">{sourceIds.length} nguồn đã chọn</span>}
    </div>
    {currentJob && <section className={`job-banner ${currentJob.status.toLowerCase()}`} aria-live="polite">
      <div className="job-banner-copy">
        <span className="job-icon"><Sparkles size={19} /></span>
        <div>
          <strong>{currentJob.status === "SUCCEEDED" ? "Đã tạo xong" : currentJob.status === "FAILED" ? "Chưa thể tạo nội dung" : "Đang chuẩn bị nội dung học"}</strong>
          <p>{kind ? artifacts.find(item => item.id === kind)?.title : "Hoạt động"}</p>
        </div>
      </div>
      {["PENDING", "RUNNING"].includes(currentJob.status) && <Progress value={currentJob.progress || 0} label="Tiến độ" />}
      {currentJob.status === "SUCCEEDED" && kind === "quiz" && currentJob.resultRef && <button className="button primary" onClick={() => setQuizId(currentJob.resultRef || null)}>Làm bài<ArrowRight size={16} /></button>}
      {currentJob.status === "SUCCEEDED" && kind === "flashcards" && <button className="button primary" onClick={onReview}>Ôn thẻ<ArrowRight size={16} /></button>}
      {currentJob.status === "SUCCEEDED" && kind === "study-guide" && <p className="success-copy"><Check size={16} />Hướng dẫn học đã được thêm vào sổ tay.</p>}
      {currentJob.status === "FAILED" && <p className="danger-copy">Mã lỗi: {currentJob.errorCode || "Không xác định"}</p>}
    </section>}
    <ErrorNotice error={generate.error || jobState.error} retry={() => { void jobState.refetch(); }} />
    <div className="studio-options">
      {artifacts.map((artifact, index) => <button
        key={artifact.id}
        className={`studio-option studio-option-${index + 1}`}
        disabled={generate.isPending || Boolean(currentJob && ["PENDING", "RUNNING"].includes(currentJob.status))}
        onClick={() => generate.mutate(artifact.id, { onSuccess: result => { setKind(result.nextKind); setJob(result.created); } })}
      >
        <span className="studio-option-icon"><artifact.icon size={24} /></span>
        <span><strong>{artifact.title}</strong><small>{artifact.description}</small></span>
        <ArrowRight size={18} />
      </button>)}
    </div>
    {!sourceIds.length && <div className="context-note"><ListChecks size={18} /><p>Chưa chọn nguồn cụ thể. Nội dung sẽ dùng tất cả tài liệu sẵn sàng trong sổ tay.</p></div>}
  </div>;
}

function QuizRunner({ quizId, onClose }: { quizId: string; onClose: () => void }) {
  const { api } = useAuth();
  const quiz = useResource<Quiz>(`/api/v1/quizzes/${quizId}`);
  const [attempt, setAttempt] = useState<QuizAttempt | null>(null);
  const [questionIndex, setQuestionIndex] = useState(0);
  const [answers, setAnswers] = useState<Record<string, unknown>>({});
  const start = useAction(() => api.post<QuizAttempt>(`/api/v1/quizzes/${quizId}/attempts`));
  const saveAnswer = useAction((value: { questionId: string; answer: unknown }) => api.post(`/api/v1/attempts/${attempt?.id}/answers`, value));
  const complete = useAction(() => api.post<QuizAttempt>(`/api/v1/attempts/${attempt?.id}/complete`));
  const questions = quiz.data?.questions || [];
  const question = questions[questionIndex];
  const progress = questions.length ? ((questionIndex + (attempt?.status === "COMPLETED" ? 1 : 0)) / questions.length) * 100 : 0;

  const answerReady = useMemo(() => {
    if (!question) return false;
    const answer = answers[question.id];
    return Array.isArray(answer) ? answer.length > 0 : String(answer ?? "").trim().length > 0;
  }, [answers, question]);

  if (quiz.isPending) return <Loading label="Đang mở bài kiểm tra…" />;
  if (quiz.error || !quiz.data) return <ErrorNotice error={quiz.error || new Error("Không tìm thấy bài kiểm tra.")} retry={() => { void quiz.refetch(); }} />;
  if (!attempt) return <div className="quiz-welcome">
    <button className="text-button" onClick={onClose}><ChevronLeft size={16} />Quay lại góc luyện tập</button>
    <span className="quiz-welcome-icon"><FileQuestion size={30} /></span>
    <h2>{quiz.data.title}</h2>
    <p className="muted">{questions.length} câu hỏi. Kết quả sẽ cập nhật mức độ nắm vững của bạn.</p>
    <button className="button primary" disabled={start.isPending} onClick={() => start.mutate(undefined, { onSuccess: setAttempt })}>{start.isPending ? "Đang bắt đầu…" : "Bắt đầu làm bài"}<ArrowRight size={16} /></button>
    <ErrorNotice error={start.error} />
  </div>;
  if (attempt.status === "COMPLETED") return <QuizResult attempt={attempt} onClose={onClose} />;
  if (!question) return <Empty title="Bài kiểm tra chưa có câu hỏi"><button className="button" onClick={onClose}>Quay lại</button></Empty>;

  const submitCurrent = () => {
    saveAnswer.mutate({ questionId: question.id, answer: answers[question.id] }, {
      onSuccess: () => {
        if (questionIndex < questions.length - 1) setQuestionIndex(index => index + 1);
        else complete.mutate(undefined, { onSuccess: setAttempt });
      },
    });
  };

  return <div className="quiz-runner">
    <div className="quiz-runner-head">
      <button className="text-button" onClick={onClose}><ChevronLeft size={16} />Thoát</button>
      <span>Câu {questionIndex + 1} / {questions.length}</span>
    </div>
    <Progress value={progress} label="Tiến độ bài làm" />
    <QuestionView question={question} value={answers[question.id]} onChange={value => setAnswers(current => ({ ...current, [question.id]: value }))} />
    <ErrorNotice error={saveAnswer.error || complete.error} />
    <div className="quiz-actions">
      <button className="button" disabled={questionIndex === 0 || saveAnswer.isPending} onClick={() => setQuestionIndex(index => Math.max(0, index - 1))}>Câu trước</button>
      <button className="button primary" disabled={!answerReady || saveAnswer.isPending || complete.isPending} onClick={submitCurrent}>{questionIndex === questions.length - 1 ? "Nộp bài" : "Câu tiếp theo"}<ArrowRight size={16} /></button>
    </div>
  </div>;
}

function QuestionView({ question, value, onChange }: { question: QuizQuestion; value: unknown; onChange: (value: unknown) => void }) {
  const options = question.type === "TRUE_FALSE" && !question.options?.length ? ["Đúng", "Sai"] : question.options || [];
  const multiple = question.type === "MULTI_SELECT";
  return <fieldset className="quiz-question">
    <legend>{question.prompt}</legend>
    {question.type === "SHORT_ANSWER" ? <label>Câu trả lời<textarea rows={5} value={String(value || "")} onChange={event => onChange(event.target.value)} placeholder="Trình bày bằng cách hiểu của bạn" autoFocus /></label> : <div className="answer-options">
      {options.map((option, index) => {
        const optionValue = String(index);
        const checked = multiple ? Array.isArray(value) && value.includes(optionValue) : value === optionValue;
        return <label key={option} className={checked ? "selected" : ""}>
          <input type={multiple ? "checkbox" : "radio"} name={question.id} value={optionValue} checked={checked} onChange={() => {
            if (!multiple) onChange(optionValue);
            else onChange(checked ? (value as string[]).filter(item => item !== optionValue) : [...(Array.isArray(value) ? value : []), optionValue]);
          }} />
          <span>{String.fromCharCode(65 + index)}</span><strong>{option}</strong>
        </label>;
      })}
    </div>}
  </fieldset>;
}

function QuizResult({ attempt, onClose }: { attempt: QuizAttempt; onClose: () => void }) {
  const score = Math.round((attempt.score || 0) * 100);
  return <div className="quiz-result">
    <span className="quiz-result-icon"><Check size={30} /></span>
    <p>Hoàn thành bài kiểm tra</p>
    <strong>{score}%</strong>
    <h2>{score >= 80 ? "Bạn đang nắm khá chắc" : score >= 60 ? "Nền tảng đã có, tiếp tục củng cố" : "Hãy xem lại các khái niệm chính"}</h2>
    <p className="muted">{attempt.masteryChanges?.length || 0} khái niệm đã được cập nhật từ kết quả này.</p>
    <button className="button primary" onClick={onClose}>Về góc luyện tập</button>
  </div>;
}
