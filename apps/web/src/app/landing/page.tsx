import type { Metadata } from "next";
import Image from "next/image";
import {
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  Check,
  ChevronDown,
  FileText,
  Layers3,
  Link2,
  MessageSquareText,
  NotebookPen,
  Route,
  Upload,
  Clapperboard,
} from "lucide-react";
import { LandingNavigation } from "@/features/landing/navigation";
import { KnowledgeScene } from "@/features/landing/knowledge-scene";
import { FlashcardDemo } from "@/features/landing/flashcard-demo";
import { LandingReveal } from "@/features/landing/reveal";
import { faqs } from "@/features/landing/content";
import {
  landingDescription,
  landingTitle,
  siteUrl,
} from "@/features/landing/seo";
import "./landing.css";

export const metadata: Metadata = {
  title: landingTitle,
  description: landingDescription,
  icons: { icon: "/images/studyos-icon.svg" },
  ...(siteUrl
    ? { metadataBase: new URL(siteUrl), alternates: { canonical: "/landing" } }
    : {}),
  robots: { index: true, follow: true },
  openGraph: {
    title: landingTitle,
    description: landingDescription,
    siteName: "StudyOS",
    locale: "vi_VN",
    type: "website",
    ...(siteUrl
      ? {
          url: `${siteUrl}/landing`,
          images: [
            {
              url: `${siteUrl}/images/study-desk.webp`,
              width: 1200,
              height: 800,
              alt: "Góc học tập với sách và sổ tay StudyOS",
            },
          ],
        }
      : {}),
  },
  twitter: {
    card: "summary_large_image",
    title: landingTitle,
    description: landingDescription,
    ...(siteUrl ? { images: [`${siteUrl}/images/study-desk.webp`] } : {}),
  },
};

export default function LandingPage() {
  const structuredData = {
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: "StudyOS",
    applicationCategory: "EducationalApplication",
    operatingSystem: "Web",
    inLanguage: "vi",
    description: landingDescription,
    ...(siteUrl ? { url: `${siteUrl}/landing` } : {}),
    featureList: [
      "Quản lý tài liệu và ghi chú",
      "Hỏi đáp có trích dẫn",
      "Quiz và flashcard",
      "Lộ trình và ôn tập",
    ],
  };
  return (
    <div className="lp-page">
      <a className="lp-skip" href="#noi-dung">
        Đến nội dung chính
      </a>
      <LandingNavigation />
      <main id="noi-dung">
        <section className="lp-container lp-hero" aria-labelledby="hero-title">
          <div className="lp-hero-copy">
            <p className="lp-eyebrow">Không gian học tập của bạn</p>
            <h1 id="hero-title">
              <span>Học có hệ thống.</span>
              <span>Nhớ có chiều sâu.</span>
            </h1>
            <p className="lp-hero-description">
              Biến tài liệu thành kiến thức với AI, flashcard và lộ trình ôn tập
              trong một không gian.
            </p>
            <div className="lp-actions">
              <a href="/" className="lp-button">
                Bắt đầu học <ArrowUpRight size={18} />
              </a>
              <a href="#tinh-nang" className="lp-text-link">
                Khám phá StudyOS <ArrowRight size={18} />
              </a>
            </div>
          </div>
          <KnowledgeScene />
        </section>

        <section
          className="lp-source-strip"
          aria-label="Các nguồn học trong StudyOS"
        >
          <div className="lp-container lp-source-inner">
            <p>
              Bắt đầu từ điều
              <br />
              <strong>bạn đang học.</strong>
            </p>
            <div>
              <FileText />
              <span>Tài liệu</span>
            </div>
            <div>
              <Link2 />
              <span>Liên kết</span>
            </div>
            <div>
              <Clapperboard />
              <span>Video</span>
            </div>
            <div>
              <NotebookPen />
              <span>Ghi chú</span>
            </div>
          </div>
        </section>

        <section
          className="lp-container lp-section lp-reveal"
          id="tinh-nang"
          aria-labelledby="features-title"
        >
          <div className="lp-section-heading">
            <h2 id="features-title">
              Bớt phân tán.
              <br />
              Thêm kết nối kiến thức.
            </h2>
            <p>Tài liệu, câu hỏi và những lần ôn tập cùng ở một nơi.</p>
          </div>
          <div className="lp-feature-grid">
            <article className="lp-feature lp-feature-photo" id="tai-lieu">
              <div className="lp-feature-copy">
                <BookOpen size={25} />
                <h3>Mỗi chủ đề, một sổ tay.</h3>
                <p>
                  Gom tài liệu và ghi chú theo mục tiêu. Dễ tìm lại, dễ tiếp
                  tục.
                </p>
              </div>
              <Image
                src="/images/study-desk.webp"
                alt="Sách mở, sổ tay xanh và ghi chú trên bàn học dưới ánh sáng tự nhiên"
                width={1200}
                height={800}
                sizes="(max-width: 767px) 100vw, 55vw"
                unoptimized
              />
            </article>
            <article className="lp-feature lp-feature-chat" id="hoi-dap">
              <MessageSquareText size={25} />
              <h3>
                Hỏi sâu hơn.
                <br />
                Có nguồn để kiểm chứng.
              </h3>
              <p>
                Đặt câu hỏi từ tài liệu và quay lại ngữ cảnh gốc qua trích dẫn.
              </p>
              <div className="lp-citation-motif" aria-hidden="true">
                <span>?</span>
                <Link2 size={26} />
                <BookOpen size={48} strokeWidth={1.2} />
              </div>
              <a href="/" className="lp-text-link">
                Bắt đầu học <ArrowUpRight size={17} />
              </a>
            </article>
            <article className="lp-feature lp-feature-lab" id="language-lab">
              <Clapperboard size={25} />
              <div>
                <h3>Biến video thành buổi luyện ngôn ngữ.</h3>
                <p>
                  Khám phá Language Lab trong sổ tay, luyện tập từ nội dung bạn
                  quan tâm.
                </p>
              </div>
              <a
                href="/"
                className="lp-round-link"
                aria-label="Bắt đầu học với Language Lab"
              >
                <ArrowUpRight size={22} />
              </a>
            </article>
          </div>
        </section>

        <section
          className="lp-practice-section"
          id="flashcard"
          aria-labelledby="practice-title"
        >
          <div className="lp-container lp-practice lp-reveal">
            <div className="lp-practice-copy">
              <p className="lp-eyebrow">Học chủ động, nhớ bền hơn</p>
              <h2 id="practice-title">
                Đọc là khởi đầu.
                <br />
                Nhớ mới là của bạn.
              </h2>
              <p>
                Tạo quiz và flashcard từ nguồn học. Tự kiểm tra để nhận ra phần
                đã hiểu và phần cần ôn lại.
              </p>
              <div className="lp-practice-points">
                <span>
                  <Check size={18} /> Ôn đúng nội dung của bạn
                </span>
                <span>
                  <Check size={18} /> Tập nhớ trước khi xem đáp án
                </span>
              </div>
              <a href="/" className="lp-text-link">
                Bắt đầu học <ArrowUpRight size={18} />
              </a>
            </div>
            <FlashcardDemo />
          </div>
        </section>

        <section
          className="lp-container lp-section lp-reveal"
          id="cach-bat-dau"
          aria-labelledby="onboard-title"
        >
          <div className="lp-section-heading">
            <h2 id="onboard-title">
              Một buổi học tốt
              <br />
              bắt đầu thật đơn giản.
            </h2>
            <p>Từ tài liệu đầu tiên đến nhịp học phù hợp với bạn.</p>
          </div>
          <ol className="lp-onboarding">
            <li>
              <span className="lp-step-icon">
                <Upload size={24} />
              </span>
              <h3>Thêm nguồn học</h3>
              <p>
                Tạo sổ tay, tải tài liệu hoặc thêm liên kết bạn muốn tìm hiểu.
              </p>
            </li>
            <li>
              <span className="lp-step-icon">
                <MessageSquareText size={24} />
              </span>
              <h3>Hỏi để hiểu sâu</h3>
              <p>
                Đặt câu hỏi, kiểm tra trích dẫn và ghi lại ý chính theo cách của
                bạn.
              </p>
            </li>
            <li>
              <span className="lp-step-icon">
                <Layers3 size={24} />
              </span>
              <h3>Ôn để nhớ lâu</h3>
              <p>
                Làm quiz, luyện flashcard và quay lại các chủ đề cần củng cố.
              </p>
            </li>
          </ol>
        </section>

        <section
          className="lp-container lp-reveal"
          id="lo-trinh"
          aria-labelledby="journey-title"
        >
          <div className="lp-journey">
            <Route size={46} strokeWidth={1.2} />
            <div>
              <h2 id="journey-title">Luôn biết bước học tiếp theo.</h2>
              <p>
                Theo dõi mức độ nắm vững kiến thức và lịch ôn tập để giữ nhịp
                học của riêng bạn.
              </p>
            </div>
            <a href="/" className="lp-button">
              Bắt đầu học <ArrowUpRight size={18} />
            </a>
          </div>
        </section>

        <section
          className="lp-container lp-section lp-faq lp-reveal"
          id="cau-hoi"
          aria-labelledby="faq-title"
        >
          <h2 id="faq-title">Bạn có thể đang thắc mắc.</h2>
          <div>
            {faqs.map(({ question, answer }) => (
              <details key={question} className="lp-faq-item">
                <summary>
                  {question}
                  <ChevronDown size={20} />
                </summary>
                <p>{answer}</p>
              </details>
            ))}
          </div>
        </section>
      </main>
      <footer className="lp-footer">
        <div className="lp-container">
          <div className="lp-footer-top">
            <div>
              <a className="lp-brand" href="/landing">
                <span className="lp-brand-mark">
                  <BookOpen size={21} />
                </span>
                Study<span className="lp-brand-os">OS</span>
              </a>
              <p>
                Không gian cho những điều
                <br />
                bạn muốn hiểu và ghi nhớ.
              </p>
            </div>
            <nav aria-label="Tính năng StudyOS">
              <strong>Khám phá</strong>
              <a href="#tai-lieu">Tài liệu & ghi chú</a>
              <a href="#hoi-dap">Hỏi đáp có trích dẫn</a>
              <a href="#flashcard">Quiz & flashcard</a>
            </nav>
            <nav aria-label="Bắt đầu với StudyOS">
              <strong>Hành trình học</strong>
              <a href="#cach-bat-dau">Cách bắt đầu</a>
              <a href="#lo-trinh">Lộ trình & ôn tập</a>
              <a href="#cau-hoi">Câu hỏi thường gặp</a>
            </nav>
            <a href="/" className="lp-text-link">
              Bắt đầu học <ArrowUpRight size={18} />
            </a>
          </div>
          <div className="lp-footer-bottom">
            <small>© {new Date().getFullYear()} StudyOS</small>
            <small>Học từ tài liệu. Hiểu bằng tư duy của bạn.</small>
          </div>
        </div>
      </footer>
      <LandingReveal />
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify(structuredData).replace(/</g, "\\u003c"),
        }}
      />
    </div>
  );
}
