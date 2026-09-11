import type { Metadata } from "next";
import { Be_Vietnam_Pro, Manrope } from "next/font/google";
import { Providers } from "@/features/auth/provider";
import "./globals.css";

const bodyFont = Be_Vietnam_Pro({
  subsets: ["latin", "vietnamese"],
  variable: "--font-body",
  weight: ["400", "500", "600", "700"],
});
const displayFont = Manrope({
  subsets: ["latin"],
  variable: "--font-display",
  weight: ["500", "600", "700", "800"],
});

export const metadata: Metadata = {
  title: "StudyOS | Không gian học tập",
  description: "Học từ tài liệu của bạn, hiểu sâu và ghi nhớ lâu.",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body className={`${bodyFont.variable} ${displayFont.variable}`}>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
