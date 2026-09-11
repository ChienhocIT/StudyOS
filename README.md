# StudyOS

StudyOS là workspace học tập từ tài liệu: nhập nguồn, hỏi đáp có trích dẫn,
tạo quiz và flashcard, cập nhật mastery và lên lịch ôn tập.

## Kiến trúc và trạng thái

- `apps/web`: Next.js, TypeScript, React Query, REST và WebSocket.
- `apps/core-api`: Java 21, Spring Boot, xác thực, phân quyền workspace và toàn bộ nghiệp vụ.
- `apps/ai-service`: FastAPI, truy xuất bằng chứng, gateway model và streaming nội bộ.
- `apps/worker`: ingestion, tạo artifact, RabbitMQ và transactional outbox.
- `packages/python-ai-core`: retrieval, provider và schema dùng chung cho Python.
- `database/migrations`: Flyway; V1 giữ nguyên thiết kế ban đầu, thay đổi mới dùng migration bổ sung.
- `Software Desgin/system desgin`: thiết kế gốc; `backlog.csv`: backlog gốc.

Xem [tiến độ và bằng chứng kiểm thử](docs/implementation/PROGRESS.md),
[quyết định triển khai](docs/implementation/DECISIONS.md) và
[review](docs/implementation/REVIEW.md).

## Chạy bằng Docker

Cần Docker Desktop đang chạy, Docker Compose v2 và PowerShell.

```powershell
./scripts/setup.ps1
./scripts/start-local.ps1 -Build
```

Mở `http://localhost:3000` và tạo tài khoản. Health của Core:
`http://localhost:8080/actuator/health`. Script tạo secret ngẫu nhiên trong `.env`
đã được Git bỏ qua; chạy lại sẽ giữ cấu hình hiện có.

Các cổng mặc định: web 3000, Core 8080, AI 8000, PostgreSQL 5432, Redis 6379,
RabbitMQ 5672/15672, MinIO 9000/9001. Nếu Redis bị chiếm, đặt `REDIS_PORT=6380`
trong `.env`; mạng nội bộ Docker vẫn dùng 6379.

```powershell
docker compose --profile app ps
docker compose --profile app logs --tail 100 core-api ai-service worker
docker compose --profile app stop
```

Lệnh `stop` giữ dữ liệu trong named volumes. Bucket MinIO ở chế độ private;
quy tắc lifecycle local chỉ dọn prefix upload tạm `staging/` sau một ngày.

## Phát triển trực tiếp trên máy

Cần Java 21, Maven 3.9+, Python 3.12, uv và Node.js 22+.
`configure_runtime.py` nạp `.env` cho từng lệnh và chọn JDK 21 cục bộ nếu có,
không đổi cấu hình Java toàn hệ thống.

```powershell
./scripts/setup.ps1
docker compose up -d --wait postgres redis rabbitmq minio
docker compose run --rm minio-init
npm --prefix apps/web ci
uv sync --project apps/ai-service --extra dev --locked
uv sync --project apps/worker --extra dev --locked
```

Chạy mỗi lệnh sau trong một terminal riêng ở thư mục gốc:

```powershell
python scripts/configure_runtime.py mvn -f apps/core-api/pom.xml spring-boot:run
python scripts/configure_runtime.py uv run --project apps/ai-service python -m app
python scripts/configure_runtime.py uv run --project apps/worker python -m worker.main
python scripts/configure_runtime.py npm --prefix apps/web run dev
```

## Model

Mặc định `MODEL_PROVIDER=local-extractive`: dùng trích xuất văn bản và hash embeddings
để chạy luồng local mà không cần API key. Đây không phải model sinh ngôn ngữ hoặc
thước đo chất lượng semantic retrieval. Chức năng dịch yêu cầu provider phù hợp.

Để dùng gateway tương thích OpenAI, cấu hình `MODEL_PROVIDER=openai-compatible`,
`MODEL_PROVIDER_API_KEY`, `MODEL_PROVIDER_BASE_URL`, `MODEL_NAME`, `EMBEDDING_MODEL`
trong `.env`. Xem cấu hình provider trong code trước khi thay model; đổi embedding
model cần lập chỉ mục lại nguồn. Giá model chỉ được tính khi cấu hình giá tương ứng;
thiếu telemetry được lưu là chưa có dữ liệu.

## Kiểm thử

```powershell
./scripts/check.ps1
```

Core integration tests dùng PostgreSQL/pgvector thật qua Testcontainers nên cần Docker.
Python tests kiểm tra tenant scope, ingestion, artifact, provider và streaming.
Web có typecheck, unit tests và production build.

Java được định dạng bằng Spotless; `mvn verify` kiểm tra định dạng cùng các test.
Chạy `python scripts/configure_runtime.py mvn -f apps/core-api/pom.xml spotless:apply`
để định dạng sau khi sửa. Cấu hình theo [tài liệu Spotless](https://github.com/diffplug/spotless/tree/main/plugin-maven).

Khi các dịch vụ local đang chạy:

```powershell
python scripts/configure_runtime.py uv run --project apps/ai-service python tests/e2e/local_smoke.py
python scripts/configure_runtime.py uv run --project apps/ai-service python tests/e2e/cleanup_smoke.py
npm --prefix apps/web exec -- playwright install chromium
npm --prefix apps/web run test:e2e
```

Smoke test tạo tài khoản riêng, kiểm tra cả RabbitMQ, PostgreSQL, MinIO, AI và
WebSocket. Báo cáo local ở `.tmp/`; Playwright lưu lỗi trong `apps/web/test-results`.
CI được khai báo trong `.github/workflows/ci.yml`; kết quả CI từ remote chỉ có sau
khi repository được kết nối và đẩy lên máy chủ Git.
