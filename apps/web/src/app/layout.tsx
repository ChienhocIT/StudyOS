import type { Metadata } from "next";
import { Providers } from "@/features/auth/provider";
import "./globals.css";
export const metadata: Metadata = { title: "StudyOS · Không gian học tập", description: "Học từ tài liệu của bạn, hiểu sâu và ghi nhớ lâu." };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="vi"><body><Providers>{children}</Providers></body></html>;
}
