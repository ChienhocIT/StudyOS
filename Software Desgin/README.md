# StudyOS Phase 0 — Software Design Package

Tài liệu thiết kế hệ thống hoàn chỉnh cho StudyOS, được đóng băng trước khi triển khai.

## Cấu trúc thư mục

```
Software Desgin/
├── README.md                    ← Bạn đang đọc
├── system desgin/               ← 9 tài liệu thiết kế chi tiết
│   ├── 01_C4_Architecture_and_System_Design.md
│   ├── 02_ERD_and_Database_Design.md
│   ├── 03_Module_and_Package_Structure.md
│   ├── 04_API_OpenAPI_Contract.md
│   ├── 05_RabbitMQ_Event_Architecture.md
│   ├── 06_WebSocket_Protocol.md
│   ├── 07_Sequence_Diagrams_Critical_Flows.md
│   ├── 08_Backlog_Epic_UserStory_Task.md
│   └── 09_ADR_and_Traceability.md
├── diagrams/                    ← 10 SVG diagrams
│   ├── c4_context.svg           ← C4 Level 1: System Context
│   ├── c4_container.svg         ← C4 Level 2: Containers
│   ├── c4_component_core.svg   ← C4 Level 3: Core API Components
│   ├── c4_component_ai.svg     ← C4 Level 3: AI Service Components
│   ├── erd_full.svg            ← Full ERD (38 tables)
│   ├── seq_01_source_ingestion.svg
│   ├── seq_02_grounded_chat.svg
│   ├── seq_03_quiz_mastery.svg
│   ├── seq_04_flashcard_review.svg
│   └── seq_05_youtube_language_lab.svg
├── contracts/                   ← Machine-readable contracts
│   ├── openapi-core.yaml        ← Browser-facing REST API (46 paths, 5064 lines)
│   ├── openapi-ai-internal.yaml ← Service-to-service AI API
│   ├── asyncapi-rabbitmq.yaml   ← RabbitMQ exchanges/queues/events
│   ├── websocket-protocol.schema.json
│   ├── websocket-examples.json
│   ├── event-envelope.schema.json
│   ├── schema.sql               ← PostgreSQL DDL baseline (554 lines)
│   └── studyos.dbml             ← DBML modeling source
└── docx/
    └── StudyOS_Phase0_Software_Design_FINAL.docx
```

## Tài liệu theo chủ đề

| # | Tài liệu | Nội dung chính |
|---|---|---|
| 01 | C4 Architecture | System Context, Container, Component diagrams; Quality Attributes; Data Ownership |
| 02 | ERD & Database | 38 tables, 22 enums, indexes, transaction boundaries, idempotency |
| 03 | Module & Package | Monorepo layout, Hexagonal architecture, ArchUnit, coding conventions |
| 04 | API Contract | 46 REST endpoints, internal AI API, pagination, error codes |
| 05 | RabbitMQ Events | 7 exchanges, 10 queues, 13 events, outbox pattern, retry/DLQ |
| 06 | WebSocket Protocol | 4 client commands, 10 server events, reconnect, replay buffer |
| 07 | Sequence Diagrams | 5 critical flows: ingestion, chat, quiz, review, language lab |
| 08 | Backlog | 11 epics, 40 user stories, 222 tasks, Sprint 0–16 |
| 09 | ADR & Traceability | 10 Architecture Decision Records, requirement traceability matrix |
