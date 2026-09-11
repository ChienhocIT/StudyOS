"use client";
import { ChartNoAxesCombined } from "lucide-react";
import { useResource } from "@/shared/api/hooks";
import { Empty, ErrorNotice, Loading } from "@/shared/ui/primitives";
const metricLabels: Record<string, string> = {
  notebooks: "Sổ tay", notebookCount: "Sổ tay", sources: "Tài liệu", sourceCount: "Tài liệu", readySources: "Tài liệu sẵn sàng", readySourceCount: "Tài liệu sẵn sàng",
  notes: "Ghi chú", noteCount: "Ghi chú", quizzes: "Bài kiểm tra", quizCount: "Bài kiểm tra", completedAttempts: "Bài đã hoàn thành", quizAttempts: "Lượt làm bài", quizAttemptsCompleted: "Bài đã hoàn thành",
  totalReviews: "Lượt ôn tập", reviews: "Lượt ôn tập", reviewCount: "Lượt ôn tập", dueCards: "Thẻ đến hạn", dueFlashcards: "Thẻ đến hạn", flashcards: "Thẻ ghi nhớ", flashcardCount: "Thẻ ghi nhớ",
  concepts: "Khái niệm", conceptCount: "Khái niệm", averageMastery: "Mức nắm vững trung bình", averageScore: "Điểm trung bình", averageQuizScore: "Điểm trung bình", streakDays: "Ngày học liên tiếp", studyMinutes: "Phút học", vocabularyItems: "Từ vựng đã lưu", messages: "Tin nhắn", conversationCount: "Hội thoại", workspaceCount: "Không gian"
};
export function AnalyticsPanel({ notebookId }: { notebookId?: string }) {
  const path = notebookId ? `/api/v1/analytics/notebooks/${notebookId}` : "/api/v1/analytics/overview";
  const overview = useResource<Record<string, unknown>>(path);
  const metrics = Object.entries(overview.data || {}).filter(([key, value]) => key in metricLabels && typeof value === "number") as [string, number][];
  return <div className="feature-panel"><div className="section-heading"><div><span className="eyebrow">NHÌN LẠI ĐỂ ĐI XA HƠN</span><h2>Tiến độ học tập</h2><p className="muted">Các chỉ số được tổng hợp từ hoạt động học tập đã lưu của bạn.</p></div><ChartNoAxesCombined size={29} className="heading-illustration" /></div><ErrorNotice error={overview.error} retry={() => { void overview.refetch(); }} />{overview.isPending ? <Loading /> : !metrics.length ? <Empty title="Chưa có dữ liệu thống kê"><p>Thêm tài liệu, làm bài kiểm tra và ôn thẻ để theo dõi tiến bộ tại đây.</p></Empty> : <div className="metrics-grid">{metrics.map(([key, value]) => <article className="metric" key={key}><span>{metricLabels[key]}</span><strong>{key.toLowerCase().includes("average") ? `${Math.round(value * 100)}%` : new Intl.NumberFormat("vi-VN").format(value)}</strong><div className="metric-rule" /></article>)}</div>}<div className="analytics-note"><ChartNoAxesCombined size={21} /><p>Một chỉ số đơn lẻ không nói hết quá trình học. Kết hợp kết quả kiểm tra, khả năng nhớ và mức độ tự tin để chọn bước tiếp theo.</p></div></div>;
}
