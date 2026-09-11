"use client";
import { useState } from "react";
import { ArrowRight, Check, Plus, Target } from "lucide-react";
import { useAuth } from "@/features/auth/provider";
import { useAction, useResource } from "@/shared/api/hooks";
import type { Entity, Goal, Mastery, Recommendation } from "@/shared/api/types";
import {
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  Progress,
  formatDate,
} from "@/shared/ui/primitives";
const actionLabels: Record<Recommendation["actionType"], string> = {
  LEARN_NEW: "Khám phá kiến thức mới",
  REVIEW_FLASHCARDS: "Ôn thẻ ghi nhớ",
  RETRY_QUIZ: "Luyện tập lại",
  EXPLAIN_CONCEPT: "Hiểu sâu khái niệm",
  FEYNMAN_PRACTICE: "Giải thích bằng lời của bạn",
  CONTINUE_SOURCE: "Tiếp tục đọc tài liệu",
};
export function LearningPanel({
  notebookId,
  workspaceId,
  onAction,
}: {
  notebookId?: string;
  workspaceId?: string;
  onAction?: (action: string) => void;
}) {
  const { api } = useAuth();
  const mastery = useResource<Mastery[]>(
    notebookId ? `/api/v1/notebooks/${notebookId}/mastery` : null,
  );
  const concepts = useResource<Entity<"Concept">[]>(
    notebookId ? `/api/v1/notebooks/${notebookId}/concepts` : null,
  );
  const recommendations = useResource<Recommendation[]>(
    `/api/v1/learning/recommendations?limit=8${notebookId ? `&notebookId=${notebookId}` : ""}`,
  );
  const goals = useResource<Goal[]>("/api/v1/learning/goals");
  const [editing, setEditing] = useState<Goal | "new" | null>(null);
  const save = useAction(
    (body: Record<string, unknown>) =>
      editing === "new"
        ? api.post<Goal>("/api/v1/learning/goals", { ...body, workspaceId })
        : api.patch<Goal>(
            `/api/v1/learning/goals/${editing && editing.id}`,
            body,
          ),
    ["/api/v1/learning/goals"],
  );
  const status = useAction(
    (value: { id: string; status: string }) =>
      api.patch(`/api/v1/learning/goals/${value.id}`, { status: value.status }),
    ["/api/v1/learning/goals"],
  );
  return (
    <div className="feature-panel">
      <div className="section-heading">
        <div>
          <span className="eyebrow">BIẾT MÌNH ĐANG Ở ĐÂU</span>
          <h2>{notebookId ? "Bản đồ kiến thức" : "Lộ trình học tập"}</h2>
          <p className="muted">
            Biến mục tiêu lớn thành những bước tiến rõ ràng.
          </p>
        </div>
        <Target size={29} className="heading-illustration" />
      </div>
      <ErrorNotice
        error={
          recommendations.error ||
          mastery.error ||
          concepts.error ||
          goals.error ||
          status.error
        }
      />
      <section className="learning-section">
        <h3>Bước học tiếp theo</h3>
        {recommendations.isPending ? (
          <Loading />
        ) : !recommendations.data?.length ? (
          <p className="empty-inline">
            Học và ôn tập để nhận gợi ý phù hợp với tiến độ của bạn.
          </p>
        ) : (
          <div className="recommendations">
            {recommendations.data.map((item) => (
              <div className="recommendation" key={item.id}>
                <span className="recommendation-icon">
                  <ArrowRight size={18} />
                </span>
                <div>
                  <h4>{actionLabels[item.actionType]}</h4>
                  <p>{recommendationReason(item.reason)}</p>
                  {item.estimatedMinutes != null && (
                    <small>Khoảng {item.estimatedMinutes} phút</small>
                  )}
                </div>
                {onAction && (
                  <button
                    className="icon-button"
                    aria-label={`Bắt đầu ${actionLabels[item.actionType]}`}
                    onClick={() => onAction(item.actionType)}
                  >
                    <ArrowRight size={18} />
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </section>
      {notebookId && (
        <section className="learning-section">
          <h3>Mức độ nắm vững</h3>
          {mastery.isPending ? (
            <Loading />
          ) : !mastery.data?.length ? (
            <Empty title="Kiến thức đang chờ được khám phá">
              <p>
                Kết quả bài kiểm tra và ôn thẻ sẽ giúp bạn thấy điểm mạnh và
                điều cần củng cố.
              </p>
            </Empty>
          ) : (
            <div className="mastery-list">
              {mastery.data.map((item) => (
                <div key={item.conceptId} className="mastery-row">
                  <Progress
                    value={item.masteryScore * 100}
                    label={
                      item.conceptName ||
                      concepts.data?.find(
                        (concept) => concept.id === item.conceptId,
                      )?.name ||
                      "Khái niệm"
                    }
                  />
                  <div className="between muted">
                    <small>{item.evidenceCount} lần đánh giá</small>
                    <small>
                      Độ tin cậy {Math.round(item.confidence * 100)}%
                    </small>
                  </div>
                </div>
              ))}
            </div>
          )}
          {Boolean(concepts.data?.length) && (
            <details className="concept-list">
              <summary>
                Các khái niệm và kiến thức tiên quyết ({concepts.data?.length})
              </summary>
              {concepts.data?.map((concept) => (
                <div key={concept.id}>
                  <strong>{concept.name}</strong>
                  <p>{concept.description}</p>
                  {Boolean(concept.prerequisiteIds?.length) && (
                    <small className="muted">
                      Nên học trước:{" "}
                      {concept.prerequisiteIds
                        ?.map(
                          (id) =>
                            concepts.data?.find((item) => item.id === id)
                              ?.name || "Khái niệm liên quan",
                        )
                        .join(", ")}
                    </small>
                  )}
                </div>
              ))}
            </details>
          )}
        </section>
      )}
      <section className="learning-section">
        <div className="between">
          <h3>Mục tiêu của bạn</h3>
          <button
            className="button small"
            disabled={!workspaceId}
            onClick={() => {
              save.reset();
              setEditing("new");
            }}
          >
            <Plus size={15} />
            Thêm mục tiêu
          </button>
        </div>
        {goals.isPending ? (
          <Loading />
        ) : !goals.data?.length ? (
          <p className="empty-inline">
            Đặt một mục tiêu cụ thể để định hướng buổi học tiếp theo.
          </p>
        ) : (
          <div className="goals-list">
            {goals.data.map((goal) => (
              <article
                className={`goal-row ${goal.status.toLowerCase()}`}
                key={goal.id}
              >
                <button
                  className="goal-check"
                  aria-label={
                    goal.status === "COMPLETED"
                      ? `Tiếp tục ${goal.title}`
                      : `Hoàn thành ${goal.title}`
                  }
                  disabled={status.isPending}
                  onClick={() =>
                    status.mutate({
                      id: goal.id,
                      status:
                        goal.status === "COMPLETED" ? "ACTIVE" : "COMPLETED",
                    })
                  }
                >
                  {goal.status === "COMPLETED" && <Check size={16} />}
                </button>
                <button
                  className="goal-body"
                  onClick={() => {
                    save.reset();
                    setEditing(goal);
                  }}
                >
                  <strong>{goal.title}</strong>
                  <p>{goal.description}</p>
                  <small>
                    {goal.weeklyMinutes
                      ? `${goal.weeklyMinutes} phút / tuần`
                      : "Chưa đặt thời lượng"}
                    {goal.targetDate
                      ? `, mục tiêu ${formatDate(goal.targetDate)}`
                      : ""}{" "}
                    ,{" "}
                    {
                      {
                        ACTIVE: "Đang thực hiện",
                        PAUSED: "Tạm dừng",
                        COMPLETED: "Hoàn thành",
                        CANCELLED: "Đã hủy",
                      }[goal.status]
                    }
                  </small>
                </button>
              </article>
            ))}
          </div>
        )}
      </section>
      {editing && (
        <Modal
          title={
            editing === "new" ? "Đặt mục tiêu học tập" : "Chỉnh sửa mục tiêu"
          }
          onClose={() => {
            if (!save.isPending) setEditing(null);
          }}
        >
          <form
            className="stack"
            onSubmit={(event) => {
              event.preventDefault();
              const form = new FormData(event.currentTarget);
              save.mutate(
                {
                  title: String(form.get("title")).trim(),
                  description: String(form.get("description")),
                  weeklyMinutes: Number(form.get("weeklyMinutes")),
                  ...(form.get("targetDate")
                    ? { targetDate: String(form.get("targetDate")) }
                    : {}),
                  ...(editing !== "new"
                    ? { status: String(form.get("status")) }
                    : {}),
                },
                { onSuccess: () => setEditing(null) },
              );
            }}
          >
            <label>
              Mục tiêu
              <input
                name="title"
                required
                maxLength={200}
                defaultValue={editing === "new" ? "" : editing.title}
                placeholder="Ví dụ: Hiểu rõ kiến trúc hướng sự kiện"
              />
            </label>
            <label>
              Mô tả
              <textarea
                name="description"
                defaultValue={
                  editing === "new" ? "" : editing.description || ""
                }
              />
            </label>
            <div className="form-columns">
              <label>
                Phút học mỗi tuần
                <input
                  name="weeklyMinutes"
                  type="number"
                  min={1}
                  max={10080}
                  defaultValue={
                    editing === "new" ? 120 : editing.weeklyMinutes || 120
                  }
                  required
                />
              </label>
              <label>
                Ngày mục tiêu
                <input
                  name="targetDate"
                  type="date"
                  defaultValue={
                    editing === "new" ? "" : editing.targetDate || ""
                  }
                />
              </label>
            </div>
            {editing !== "new" && (
              <label>
                Trạng thái
                <select name="status" defaultValue={editing.status}>
                  <option value="ACTIVE">Đang thực hiện</option>
                  <option value="PAUSED">Tạm dừng</option>
                  <option value="COMPLETED">Hoàn thành</option>
                  <option value="CANCELLED">Hủy mục tiêu</option>
                </select>
              </label>
            )}
            <ErrorNotice error={save.error} />
            <button className="button primary" disabled={save.isPending}>
              Lưu mục tiêu
            </button>
          </form>
        </Modal>
      )}
    </div>
  );
}
function recommendationReason(reason: Record<string, unknown>) {
  if (typeof reason.message === "string") return reason.message;
  if (typeof reason.description === "string") return reason.description;
  if (typeof reason.explanation === "string") return reason.explanation;
  const parts: string[] = [];
  if (typeof reason.dueCount === "number")
    parts.push(`${reason.dueCount} thẻ đã đến hạn ôn`);
  if (typeof reason.masteryScore === "number")
    parts.push(
      `Mức nắm vững hiện tại ${Math.round(reason.masteryScore * 100)}%`,
    );
  if (typeof reason.conceptName === "string") parts.push(reason.conceptName);
  return (
    parts.join(", ") ||
    "Đề xuất dựa trên kiến thức và hoạt động học tập của bạn."
  );
}
