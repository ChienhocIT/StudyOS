"use client";
import { useState, type FormEvent } from "react";
import { ArrowRight, BookOpen, Check, Layers3 } from "lucide-react";
import { useAuth } from "./provider";
import { ErrorNotice } from "@/shared/ui/primitives";
export function AuthScreen() {
  const { authenticate } = useAuth();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return;
    const data = new FormData(event.currentTarget); setPending(true); setError(null);
    try { await authenticate(mode, { email: String(data.get("email")).trim(), password: String(data.get("password")), ...(mode === "register" ? { displayName: String(data.get("displayName")).trim() } : {}) }); }
    catch (err) { setError(err); } finally { setPending(false); }
  }
  return <main className="auth-shell"><section className="auth-story"><a className="brand" href="/"><span className="brand-mark"><BookOpen size={23} /></span>Study<span>OS</span></a><div className="auth-story-copy"><span className="eyebrow">KHÔNG GIAN HỌC TẬP CỦA BẠN</span><h1>Mỗi tài liệu.<br />Một bước hiểu sâu.</h1><p>Kết nối điều bạn đọc, điều bạn hiểu và điều bạn ghi nhớ — trong cùng một không gian.</p><div className="study-loop"><div><BookOpen size={22} /><span>Đọc & khám phá</span></div><span className="loop-line" /><div><Layers3 size={22} /><span>Hiểu & kết nối</span></div><span className="loop-line" /><div><Check size={22} /><span>Ôn & ghi nhớ</span></div></div></div><small>Tài liệu của bạn là điểm bắt đầu.</small></section><section className="auth-form-area"><div className="auth-form-card"><span className="eyebrow">BẮT ĐẦU MỘT BUỔI HỌC TỐT</span><h2>{mode === "login" ? "Chào mừng trở lại" : "Tạo không gian của bạn"}</h2><p className="muted">{mode === "login" ? "Đăng nhập để tiếp tục nơi bạn đã dừng lại." : "Một tài khoản cho tài liệu, ghi chú và tiến độ học tập."}</p><form onSubmit={submit} className="stack">{mode === "register" && <label>Tên hiển thị<input name="displayName" autoComplete="name" required maxLength={120} placeholder="Tên của bạn" /></label>}<label>Email<input name="email" type="email" autoComplete="email" required placeholder="ban@example.com" /></label><label>Mật khẩu<input name="password" type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} required minLength={8} maxLength={256} placeholder="Ít nhất 8 ký tự" /></label><ErrorNotice error={error} /><button className="button primary full" disabled={pending}>{pending ? "Đang xử lý…" : mode === "login" ? "Đăng nhập" : "Tạo tài khoản"}<ArrowRight size={17} /></button></form><p className="auth-switch">{mode === "login" ? "Chưa có tài khoản?" : "Đã có tài khoản?"} <button className="text-button" onClick={() => { setMode(mode === "login" ? "register" : "login"); setError(null); }}>{mode === "login" ? "Tạo tài khoản" : "Đăng nhập"}</button></p></div></section></main>;
}
