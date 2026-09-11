"use client";
import { useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowUpRight,
  BookOpen,
  ChartNoAxesCombined,
  ChevronDown,
  CirclePlus,
  FolderOpen,
  GraduationCap,
  Layers3,
  LogOut,
  Menu,
  MoreHorizontal,
  Search,
  Settings2,
  Target,
  X,
} from "lucide-react";
import { useAuth } from "@/features/auth/provider";
import { AuthScreen } from "@/features/auth/auth-screen";
import { useAction, useResource } from "@/shared/api/hooks";
import type { Notebook, Workspace } from "@/shared/api/types";
import {
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  formatDate,
} from "@/shared/ui/primitives";
import { NotebookWorkspace } from "@/features/notebook/notebook-workspace";
import { ReviewPanel } from "@/features/review/review-panel";
import { LearningPanel } from "@/features/learning/learning-panel";
import { AnalyticsPanel } from "@/features/learning/analytics-panel";

type View = "notebooks" | "review" | "learning" | "analytics";
const navigation = [
  { id: "notebooks", label: "Sổ tay của tôi", icon: BookOpen },
  { id: "review", label: "Ôn tập hôm nay", icon: Layers3 },
  { id: "learning", label: "Lộ trình học tập", icon: Target },
  { id: "analytics", label: "Tiến độ học tập", icon: ChartNoAxesCombined },
] as const;
export function StudyApp() {
  const { ready, user } = useAuth();
  if (!ready)
    return (
      <main className="boot-screen">
        <BookOpen size={34} />
        <Loading label="Đang mở không gian học tập…" />
      </main>
    );
  if (!user) return <AuthScreen />;
  return <AuthenticatedApp key={user.id} />;
}
function AuthenticatedApp() {
  const { user, api, logout } = useAuth();
  const workspaces = useResource<Workspace[]>("/api/v1/workspaces");
  const [workspaceId, setWorkspaceId] = useState("");
  const currentWorkspace =
    workspaces.data?.find((item) => item.id === workspaceId) ||
    workspaces.data?.[0];
  const notebookPath = currentWorkspace
    ? `/api/v1/workspaces/${currentWorkspace.id}/notebooks`
    : null;
  const notebooks = useResource<Notebook[]>(notebookPath);
  const [activeNotebook, setActiveNotebook] = useState<Notebook | null>(null);
  const [view, setView] = useState<View>("notebooks");
  const [search, setSearch] = useState("");
  const [dialog, setDialog] = useState<
    "workspace" | "notebook" | "settings" | null
  >(null);
  const [sidebar, setSidebar] = useState(false);
  const [logoutError, setLogoutError] = useState<unknown>(null);
  const createWorkspace = useAction(
    (name: string) => api.post<Workspace>("/api/v1/workspaces", { name }),
    ["/api/v1/workspaces"],
  );
  const createNotebook = useAction(
    (body: { title: string; description: string; goalText: string }) =>
      api.post<Notebook>(notebookPath!, body),
    notebookPath ? [notebookPath] : [],
  );
  const renameWorkspace = useAction(
    (name: string) =>
      api.patch(`/api/v1/workspaces/${currentWorkspace?.id}`, { name }),
    ["/api/v1/workspaces"],
  );
  const archiveNotebook = useAction(
    (notebook: Notebook) =>
      api.patch<Notebook>(`/api/v1/notebooks/${notebook.id}`, {
        status: notebook.status === "ACTIVE" ? "ARCHIVED" : "ACTIVE",
      }),
    notebookPath ? [notebookPath] : [],
  );
  function navigate(next: View) {
    setActiveNotebook(null);
    setView(next);
    setSidebar(false);
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (dialog === "workspace") {
      const result = await createWorkspace.mutateAsync(
        String(form.get("name")).trim(),
      );
      setWorkspaceId(result.id);
    } else if (dialog === "settings")
      await renameWorkspace.mutateAsync(String(form.get("name")).trim());
    else {
      const result = await createNotebook.mutateAsync({
        title: String(form.get("title")).trim(),
        description: String(form.get("description")).trim(),
        goalText: String(form.get("goalText")).trim(),
      });
      setActiveNotebook(result);
    }
    setDialog(null);
  }
  const pending =
    createWorkspace.isPending ||
    createNotebook.isPending ||
    renameWorkspace.isPending;
  const filtered = (notebooks.data || []).filter((item) =>
    item.title.toLocaleLowerCase("vi").includes(search.toLocaleLowerCase("vi")),
  );
  return (
    <div className={`app-shell ${sidebar ? "sidebar-open" : ""}`}>
      <a className="skip-link" href="#main">
        Đến nội dung chính
      </a>
      {sidebar && (
        <button
          className="sidebar-backdrop"
          aria-label="Đóng menu"
          onClick={() => setSidebar(false)}
        />
      )}
      <aside className="sidebar">
        <a
          href="/"
          className="brand"
          onClick={(event) => {
            event.preventDefault();
            navigate("notebooks");
          }}
        >
          <span className="brand-mark">
            <BookOpen size={23} />
          </span>
          Study<span>OS</span>
        </a>
        <div className="workspace-select">
          <label className="sr-only" htmlFor="workspace">
            Không gian học tập
          </label>
          <select
            id="workspace"
            value={currentWorkspace?.id || ""}
            onChange={(event) => {
              setWorkspaceId(event.target.value);
              setActiveNotebook(null);
            }}
          >
            <option value="" disabled>
              Chọn không gian
            </option>
            {workspaces.data?.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
          <ChevronDown size={14} />
        </div>
        <button
          className="text-button workspace-add"
          onClick={() => {
            createWorkspace.reset();
            setDialog("workspace");
          }}
        >
          <CirclePlus size={14} />
          Không gian mới
        </button>
        <nav aria-label="Điều hướng chính">
          {navigation.map((item) => (
            <button
              key={item.id}
              className={`nav-item ${view === item.id ? "active" : ""}`}
              onClick={() => navigate(item.id)}
            >
              <item.icon size={19} />
              {item.label}
            </button>
          ))}
        </nav>
        <div className="sidebar-section-title">SỔ TAY GẦN ĐÂY</div>
        <div className="recent-notebooks">
          {notebooks.data
            ?.filter((item) => item.status === "ACTIVE")
            .slice(0, 6)
            .map((item) => (
              <button
                key={item.id}
                className={`recent-item ${activeNotebook?.id === item.id ? "selected" : ""}`}
                onClick={() => {
                  setActiveNotebook(item);
                  setView("notebooks");
                  setSidebar(false);
                }}
              >
                <span className="notebook-dot" />
                {item.title}
              </button>
            ))}
          {notebooks.data?.length === 0 && (
            <p className="sidebar-hint">Sổ tay mới sẽ xuất hiện ở đây.</p>
          )}
        </div>
        <div className="sidebar-bottom">
          <div className="workspace-caption">
            <GraduationCap size={21} />
            <div>
              <strong>Học theo nhịp của bạn</strong>
              <span>Một chút tiến bộ mỗi ngày.</span>
            </div>
          </div>
          <button
            className="nav-item"
            onClick={() => {
              renameWorkspace.reset();
              setDialog("settings");
            }}
            disabled={!currentWorkspace || currentWorkspace.role === "MEMBER"}
          >
            <Settings2 size={18} />
            Cài đặt không gian
          </button>
          <div className="user-row">
            <span className="avatar">
              {user?.displayName.slice(0, 1).toUpperCase()}
            </span>
            <div>
              <strong>{user?.displayName}</strong>
              <small>{currentWorkspace?.plan || "Cá nhân"}</small>
            </div>
            <button
              className="icon-button"
              aria-label="Đăng xuất"
              onClick={() => {
                void logout().catch(setLogoutError);
              }}
            >
              <LogOut size={18} />
            </button>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="row">
            <button
              className="icon-button mobile-menu"
              aria-label="Mở menu"
              onClick={() => setSidebar(true)}
            >
              <Menu size={21} />
            </button>
            {activeNotebook ? (
              <button
                className="breadcrumb"
                onClick={() => setActiveNotebook(null)}
              >
                <ArrowLeft size={16} />
                Sổ tay của tôi
              </button>
            ) : (
              <span className="breadcrumb">
                Không gian học tập <span>/</span>{" "}
                {navigation.find((item) => item.id === view)?.label}
              </span>
            )}
          </div>
          <span className="topbar-date">
            {new Intl.DateTimeFormat("vi-VN", {
              weekday: "long",
              day: "numeric",
              month: "long",
            }).format(new Date())}
          </span>
        </header>
        <main
          id="main"
          className={activeNotebook ? "notebook-main" : "page-content"}
        >
          <ErrorNotice
            error={workspaces.error || logoutError}
            retry={() => {
              void workspaces.refetch();
            }}
          />
          {workspaces.isPending ? (
            <Loading />
          ) : activeNotebook && currentWorkspace ? (
            <NotebookWorkspace
              key={activeNotebook.id}
              notebook={activeNotebook}
              onChanged={setActiveNotebook}
            />
          ) : view === "review" ? (
            <ReviewPanel />
          ) : view === "learning" ? (
            <LearningPanel workspaceId={currentWorkspace?.id} />
          ) : view === "analytics" ? (
            <AnalyticsPanel />
          ) : (
            <>
              <div className="page-heading">
                <div>
                  <span className="eyebrow">THƯ VIỆN CÁ NHÂN</span>
                  <h1>
                    Sổ tay của tôi<span className="heading-dot">.</span>
                  </h1>
                  <p className="muted">
                    Một nơi để bắt đầu, kết nối và đi sâu vào những điều bạn
                    học.
                  </p>
                </div>
                <button
                  className="button primary"
                  disabled={!currentWorkspace}
                  onClick={() => {
                    createNotebook.reset();
                    setDialog("notebook");
                  }}
                >
                  <CirclePlus size={18} />
                  Tạo sổ tay
                </button>
              </div>
              <div className="library-toolbar">
                <div className="library-count">
                  <FolderOpen size={18} />
                  {notebooks.data?.length ?? 0} sổ tay
                </div>
                <label className="search-field">
                  <Search size={17} />
                  <input
                    aria-label="Tìm sổ tay"
                    placeholder="Tìm sổ tay…"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                  />
                </label>
              </div>
              <ErrorNotice
                error={notebooks.error || archiveNotebook.error}
                retry={() => {
                  void notebooks.refetch();
                }}
              />
              {notebooks.isPending && notebookPath ? (
                <Loading />
              ) : !currentWorkspace ? (
                <Empty title="Bắt đầu với không gian học tập đầu tiên">
                  <p>Tạo một không gian để sắp xếp tài liệu và lộ trình học.</p>
                  <button
                    className="button primary"
                    onClick={() => setDialog("workspace")}
                  >
                    Tạo không gian
                  </button>
                </Empty>
              ) : filtered.length === 0 ? (
                <Empty
                  title={
                    search
                      ? "Không tìm thấy sổ tay"
                      : "Bạn muốn tìm hiểu điều gì?"
                  }
                >
                  <p>
                    {search
                      ? "Thử một từ khóa khác."
                      : "Tạo sổ tay cho một môn học, một kỹ năng hoặc một câu hỏi bạn đang theo đuổi."}
                  </p>
                  {!search && (
                    <button
                      className="button primary"
                      onClick={() => setDialog("notebook")}
                    >
                      Tạo sổ tay đầu tiên
                    </button>
                  )}
                </Empty>
              ) : (
                <div className="notebook-grid">
                  {filtered.map((item, index) => (
                    <article
                      className={`notebook-card tone-${index % 4}`}
                      key={item.id}
                    >
                      <button
                        className="notebook-open"
                        onClick={() => setActiveNotebook(item)}
                      >
                        <div className="between">
                          <span className="notebook-emblem">
                            <BookOpen size={23} />
                          </span>
                          <ArrowUpRight size={19} />
                        </div>
                        <h2>{item.title}</h2>
                        <p>
                          {item.description ||
                            item.goalText ||
                            "Mở sổ tay để thêm tài liệu và bắt đầu học."}
                        </p>
                        <div className="notebook-card-meta">
                          <span>
                            {item.status === "ARCHIVED"
                              ? "Đã lưu trữ"
                              : "Đang học"}
                          </span>
                          <span>
                            {formatDate(item.updatedAt || item.createdAt)}
                          </span>
                        </div>
                      </button>
                      <details className="notebook-menu">
                        <summary aria-label={`Thao tác ${item.title}`}>
                          <MoreHorizontal size={18} />
                        </summary>
                        <button
                          onClick={() => archiveNotebook.mutate(item)}
                          disabled={archiveNotebook.isPending}
                        >
                          {item.status === "ACTIVE"
                            ? "Lưu trữ sổ tay"
                            : "Khôi phục sổ tay"}
                        </button>
                      </details>
                    </article>
                  ))}
                </div>
              )}
              <div className="library-footnote">
                <span className="small-rule" />
                <p>
                  Từ đọc hiểu đến ghi nhớ. Mỗi sổ tay giữ trọn hành trình của
                  bạn.
                </p>
              </div>
            </>
          )}
        </main>
      </div>
      {dialog && (
        <Modal
          title={
            dialog === "workspace"
              ? "Tạo không gian học tập"
              : dialog === "settings"
                ? "Cài đặt không gian"
                : "Tạo sổ tay mới"
          }
          onClose={() => {
            if (!pending) setDialog(null);
          }}
        >
          <form
            className="stack"
            onSubmit={(event) => {
              void submit(event).catch(() => {});
            }}
          >
            {dialog !== "notebook" ? (
              <label>
                Tên không gian
                <input
                  name="name"
                  required
                  maxLength={160}
                  defaultValue={
                    dialog === "settings" ? currentWorkspace?.name : ""
                  }
                  autoFocus
                />
              </label>
            ) : (
              <>
                <label>
                  Tên sổ tay
                  <input
                    name="title"
                    required
                    maxLength={200}
                    placeholder="Ví dụ: Kiến trúc phần mềm"
                    autoFocus
                  />
                </label>
                <label>
                  Mô tả
                  <textarea
                    name="description"
                    rows={2}
                    placeholder="Chủ đề bạn sẽ khám phá…"
                  />
                </label>
                <label>
                  Mục tiêu học tập
                  <textarea
                    name="goalText"
                    rows={2}
                    placeholder="Bạn muốn hiểu hoặc làm được điều gì?"
                  />
                </label>
              </>
            )}
            <ErrorNotice
              error={
                createWorkspace.error ||
                createNotebook.error ||
                renameWorkspace.error
              }
            />
            <div className="modal-actions">
              <button
                className="button"
                type="button"
                disabled={pending}
                onClick={() => setDialog(null)}
              >
                Hủy
              </button>
              <button className="button primary" disabled={pending}>
                {pending
                  ? "Đang lưu…"
                  : dialog === "settings"
                    ? "Lưu thay đổi"
                    : "Tạo mới"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
