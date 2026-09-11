# StudyOS — tiến độ triển khai

Cập nhật: 11/09/2026. Branch: `feat/studyos-implementation`.
Thiết kế gốc: `2345ef0`; mốc implementation đầu: `6d03440`.
Repository Git local đã có lịch sử; chưa cấu hình remote hoặc triển khai production.

## Trạng thái theo phần

| Phần | Đã triển khai | Kiểm chứng hiện tại |
|---|---|---|
| Foundation | Monorepo, Compose, migrations V1–V5, scripts, CI, contracts | 4 app images build; full Compose chạy; migration trên DB thật |
| Identity / tenancy | Đăng ký, đăng nhập, refresh rotation/revocation, workspace, notebook | Tenant-negative tests và browser registration |
| Sources | Raw/URL/upload, immutable upload sealing, state events, download, delete | Raw ingestion thật đến READY; upload/download negative tests |
| Conversations | WS ticket, replay, history paging, grounded answers, citations, cancel | Chat thật Core→AI→Redis→browser; test upstream ngừng phát dữ liệu |
| Studio | Async quiz, flashcards, study guide; provenance validation; giấu đáp án | RabbitMQ worker thật; quiz scoring và retry idempotent |
| Learning | Concepts, weighted mastery, goals, recommendations | Quiz thật cập nhật mastery; transactional integration tests |
| Review | Due queue, scheduler v0, grading idempotency | Review thật; chặn ôn chưa đến hạn và tenant khác |
| Language | Transcript/sentence analysis gateway, vocabulary, vocabulary card | Python/unit coverage; dịch cần model provider thật |
| Analytics / usage | Learning audit consumer, operational overview, AI reservations | Overview sau quiz/review; quota và expiry tests |
| Web | Auth, workspace, notebook, nguồn, chat, notes, studio, review, learning, language | Typecheck, unit, production build, browser workflow desktop/mobile |

## Bằng chứng kiểm thử

| Bộ kiểm tra | Kết quả |
|---|---|
| Core `mvn clean verify` | 36 passed, 0 failed, 0 skipped |
| Trong Core: architecture | 4 quy tắc được thực thi bằng Jupiter/ArchUnit |
| Trong Core: integration | 10 Core + 4 Learning trên PostgreSQL/pgvector Testcontainers |
| AI pytest | 32 passed |
| Worker pytest | 36 passed |
| Web Vitest | 4 passed |
| Web TypeScript + production build | Passed |
| Playwright Chromium | 1 workflow passed trên cả dev server và Docker production web: registration → notebook → source → notes → mobile navigation |
| Smoke xuyên dịch vụ | 10 nhóm kiểm tra passed trên cả native runtime và full Docker Compose; provider `local-extractive` |
| Docker build | Core, web, AI, worker đều build thành công; Python images dùng `uv.lock` |
| Contracts | YAML references, JSON schemas, 2 WS example frames và 7 internal operation IDs passed |

Smoke thực hiện trên dịch vụ thật với tài khoản riêng: tenant isolation; ingestion;
grounded WebSocket chat; quiz và mastery; flashcard scheduling; ingestion tiếp tục
sau learning events; analytics; study guide có references; xóa nguồn vô hiệu hóa
quiz, review cards và nội dung study guide. Kết quả local: `.tmp/e2e-result.json`.
Đây là kiểm chứng hành vi local, không phải đánh giá chất lượng model production.

## Review và lỗi đã sửa

Các agent đã chia phần Core, Python, Web; có review độc lập theo standards và spec.
Xem [REVIEW.md](REVIEW.md) để biết từng lỗi và bằng chứng sửa.
Đợt này sửa CORS, hủy upstream, quota bị treo khi timeout, artifact provenance,
concept extraction từ Markdown, stale artifacts và outbox retry starvation.
Một số agent chạm giới hạn sử dụng; coordinator tiếp quản chạy test và tích hợp.
Java đã được chuẩn hóa bằng Spotless; 36 test chạy lại sau định dạng vẫn đạt.
Ảnh desktop/mobile được xem lại; Playwright chờ menu đóng và chụp khi hiệu ứng ổn định.

## Phần còn lại

Không gán tỷ lệ hoàn thành cho toàn dự án khi acceptance criteria chưa kiểm đủ.
Các hạng mục cần tiếp tục theo backlog gốc:

1. Provider thật: chất lượng retrieval/citation, translation, token/cost telemetry,
   embedding accounting và giới hạn chi tiêu tiền thực tế.
2. Production: database/IAM least privilege, OAuth, deployment infrastructure,
   monitoring/alerting, backup/restore, migration rollback và secret rotation drills.
3. Mở rộng E2E: file upload/download thật, URL/transcript/video, reconnect/cancel qua
   nhiều node, accessibility và nhiều browser; kiểm thử tải và fault injection.
4. Rà lại toàn bộ acceptance criteria trong backlog, đặc biệt vận hành/beta,
   rồi mới đánh dấu hoàn tất từng story. File backlog gốc được giữ nguyên.

## Chạy và xem ứng dụng

Xem [README](../../README.md). Web local: `http://localhost:3000`.
Trong máy hiện tại Redis StudyOS dùng cổng host 6380 do 6379 thuộc dự án khác.
Compose nội bộ vẫn dùng cổng Redis 6379. Không dừng container của dự án khác.
