// Set the public deployment origin at build time. Do not invent a production domain.
export const siteUrl = process.env.NEXT_PUBLIC_SITE_URL
  ? new URL(process.env.NEXT_PUBLIC_SITE_URL).origin
  : undefined;

export const landingTitle = "StudyOS | Học từ tài liệu, hiểu sâu và nhớ lâu";
export const landingDescription =
  "Không gian học tập StudyOS: quản lý tài liệu, hỏi đáp AI có trích dẫn, tạo quiz và flashcard, theo dõi kiến thức và lên lịch ôn tập.";
