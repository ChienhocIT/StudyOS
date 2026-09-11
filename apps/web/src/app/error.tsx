"use client";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return <main className="fatal-error"><h1>Không tải được không gian học tập</h1><p>Hãy tải lại trang để tiếp tục.</p><button className="button primary" onClick={reset}>Thử lại</button></main>;
}
