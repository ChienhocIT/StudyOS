import asyncio
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response, StreamingResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from psycopg_pool import AsyncConnectionPool
from studyos_ai.config import Settings
from studyos_ai.contracts import (
    ArtifactRequest,
    ChatRequest,
    LanguageRequest,
    RetrievalRequest,
)
from studyos_ai.errors import PipelineError
from studyos_ai.generation import analyze_language, generate_answer, generate_artifact
from studyos_ai.providers import build_provider
from studyos_ai.retrieval import (
    RETRIEVAL_VERSION,
    ChunkRepository,
    build_context,
    retrieve,
)
from studyos_ai.security import validate_internal_token
from studyos_ai.telemetry import operation


def create_app(
    settings: Settings | None = None, repository=None, provider=None
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application):
        actual = settings or Settings()
        application.state.settings = actual
        application.state.provider = provider or build_provider(actual)
        application.state.semaphore = asyncio.Semaphore(actual.worker_concurrency)
        pool = None
        if repository is None:
            pool = AsyncConnectionPool(
                actual.database_read_url.get_secret_value(),
                min_size=0,
                max_size=8,
                open=False,
                kwargs={"autocommit": True},
                timeout=5,
            )
            await pool.open()
        application.state.pool = pool
        application.state.repository = repository or ChunkRepository(pool)
        logging.basicConfig(level=logging.INFO, format="%(message)s")
        yield
        if pool:
            await pool.close()
        if hasattr(application.state.provider, "close"):
            await application.state.provider.close()

    application = FastAPI(
        title="StudyOS Internal AI",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    @application.exception_handler(PipelineError)
    async def pipeline_error(request, exc):
        status = (
            401
            if exc.code == "INTERNAL_AUTH_INVALID"
            else (
                503 if exc.retryable or exc.code == "AI_CAPABILITY_UNAVAILABLE" else 422
            )
        )
        return JSONResponse(
            status_code=status,
            content={
                "code": exc.code,
                "message": exc.safe_detail,
                "retryable": exc.retryable,
            },
        )

    @application.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        # FastAPI's default input echo can expose signed context or source text in errors.
        return JSONResponse(
            status_code=422,
            content={
                "code": "VALIDATION_FAILED",
                "message": "Invalid internal request shape.",
                "retryable": False,
            },
        )

    @application.middleware("http")
    async def request_limits(request: Request, call_next):
        if request.method == "POST":
            length = request.headers.get("content-length", "")
            if length and (not length.isdigit() or int(length) > 300000):
                return JSONResponse(
                    status_code=413, content={"code": "REQUEST_TOO_LARGE"}
                )
        try:
            return await call_next(request)
        except Exception:
            logging.getLogger("studyos.ai").error(
                '{"service":"studyos-ai","status":"internal_error"}'
            )
            return JSONResponse(
                status_code=503,
                content={
                    "code": "AI_SERVICE_UNAVAILABLE",
                    "message": "AI service is temporarily unavailable.",
                    "retryable": True,
                },
            )

    def authorize(request: Request, context, permission):
        header = request.headers.get("authorization", "")
        if not header.startswith("Bearer "):
            raise PipelineError(
                "INTERNAL_AUTH_INVALID", "A signed internal token is required."
            )
        validate_internal_token(
            header[7:], context, permission, request.app.state.settings
        )

    @application.get("/health/live")
    async def live():
        return {"status": "UP", "service": "studyos-ai"}

    @application.get("/health/ready")
    async def ready(request: Request):
        if request.app.state.pool:
            try:
                async with request.app.state.pool.connection() as conn:
                    await conn.execute("SELECT 1 FROM document_chunks LIMIT 0")
            except Exception:
                return JSONResponse(
                    status_code=503,
                    content={"status": "DOWN", "database": "unavailable"},
                )
        local = request.app.state.settings.model_provider == "local-extractive"
        return {
            "status": "UP",
            "provider": request.app.state.settings.model_provider,
            "capabilities": {
                "extractiveChat": True,
                "generativeChat": not local,
                "translation": not local,
            },
            "externalProviderVerified": False,
        }

    @application.get("/metrics")
    async def metrics():
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @application.post("/internal/v1/chat/stream")
    async def chat(body: ChatRequest, request: Request):
        authorize(request, body.context, "chat:generate")
        state = request.app.state

        async def events():
            def event(kind, payload):
                return (
                    json.dumps({"type": kind, "payload": payload}, ensure_ascii=False)
                    + "\n"
                )

            yield event(
                "assistant.started",
                {"messageId": str(body.message_id), "traceId": body.context.trace_id},
            )
            try:
                async with state.semaphore:
                    with operation("chat", state.provider.model, body.context.trace_id):
                        chunks = await retrieve(
                            state.repository,
                            state.provider,
                            body.context,
                            body.content,
                            state.settings.retrieval_limit,
                        )
                        evidence = build_context(
                            chunks, state.settings.context_token_budget
                        )
                        answer = await generate_answer(body, evidence, state.provider)
                        # Validate the whole candidate before any content reaches Core/browser.
                        for index in range(0, len(answer.content), 96):
                            if await request.is_disconnected():
                                return
                            yield event(
                                "assistant.delta",
                                {"delta": answer.content[index : index + 96]},
                            )
                            await asyncio.sleep(0)
                        yield event("assistant.completed", answer.payload())
            except PipelineError as exc:
                yield event(
                    "assistant.failed",
                    {
                        "code": exc.code,
                        "message": exc.safe_detail,
                        "retryable": exc.retryable,
                    },
                )
            except Exception:
                yield event(
                    "assistant.failed",
                    {
                        "code": "AI_SERVICE_UNAVAILABLE",
                        "message": "AI service is temporarily unavailable.",
                        "retryable": True,
                    },
                )

        return StreamingResponse(
            events(),
            media_type="application/x-ndjson",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    @application.post("/internal/v1/retrieval/debug")
    async def debug(body: RetrievalRequest, request: Request):
        authorize(request, body.context, "retrieval:debug")
        state = request.app.state
        if not state.settings.debug_retrieval_enabled:
            raise HTTPException(status_code=404)
        chunks = await retrieve(
            state.repository,
            state.provider,
            body.context,
            body.query,
            state.settings.retrieval_limit,
        )
        return {
            "configVersion": RETRIEVAL_VERSION,
            "chunks": [
                {
                    "chunkId": c.id,
                    "sourceId": c.source_id,
                    "score": c.score,
                    "text": c.text,
                }
                for c in chunks
            ],
        }

    @application.post("/internal/v1/artifacts/generate")
    async def artifact(body: ArtifactRequest, request: Request):
        authorize(request, body.context, "artifact:generate")
        state = request.app.state
        async with state.semaphore:
            with operation("artifact", state.provider.model, body.context.trace_id):
                evidence = build_context(
                    await state.repository.sample(body.context),
                    state.settings.context_token_budget,
                )
                return await generate_artifact(
                    body.artifact_type, body.options, evidence, state.provider
                )

    @application.post("/internal/v1/language/analyze")
    async def language(body: LanguageRequest, request: Request):
        authorize(request, body.context, "language:analyze")
        state = request.app.state
        async with state.semaphore:
            with operation("language", state.provider.model, body.context.trace_id):
                return await analyze_language(body, state.provider)

    return application


app = create_app()
