import asyncio
import hashlib
import json
from dataclasses import dataclass
from uuid import UUID, uuid5

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from studyos_ai.config import Settings
from studyos_ai.contracts import InternalContext
from studyos_ai.egress import fetch_public_url
from studyos_ai.errors import PipelineError
from studyos_ai.generation import generate_artifact
from studyos_ai.ingestion import (
    PARSER_VERSION,
    Section,
    chunk_sections,
    concept_candidates,
    normalized_payload,
    parse_source,
)
from studyos_ai.retrieval import RETRIEVAL_VERSION, ChunkRepository, build_context
from studyos_ai.storage import ObjectStore
from studyos_ai.telemetry import operation

from .events import Event
from .repository import artifact_scope, finish_event, lock_event, seen, source_scope
from .transcripts import fetch_transcript


@dataclass
class Prepared:
    facts: list[Event]
    sections: list[Section] | None = None
    chunks: list[dict] | None = None
    vectors: list[list[float]] | None = None
    segments: list | None = None
    normalized_data: bytes | None = None


class Pipeline:
    def __init__(self, settings: Settings, pool, provider, storage=None):
        self.settings, self.pool, self.provider = settings, pool, provider
        self.storage = storage or ObjectStore(settings)
        self.repository = ChunkRepository(pool)

    @staticmethod
    def normalized_key(event: Event):
        return f"{event.workspace_id}/{event.payload['sourceId']}/{event.payload['sourceVersionId']}/normalized-v1.json"

    async def handle(self, consumer: str, event: Event) -> bool:
        artifact = event.event_type.endswith("generate.requested.v1")
        async with self.pool.connection() as conn:
            if await seen(conn, consumer, event.event_id):
                return False
            scope = await (
                artifact_scope(conn, event) if artifact else source_scope(conn, event)
            )
        # Parsing/network/model work runs outside a database transaction.
        with operation(
            "artifact-worker" if artifact else "source-worker",
            self.provider.model,
            event.trace_id,
        ):
            prepared = await (
                self.prepare_artifact(event, scope)
                if artifact
                else self.prepare_source(event, scope)
            )
        async with self.pool.connection() as conn:
            async with conn.transaction():
                await lock_event(conn, consumer, event.event_id)
                if await seen(conn, consumer, event.event_id):
                    return False
                scope = await (
                    artifact_scope(conn, event, lock=True)
                    if artifact
                    else source_scope(conn, event, lock=True)
                )
                if not artifact:
                    await self.persist_derived(conn, event, scope, prepared)
                await finish_event(conn, consumer, event, prepared.facts)
        return True

    async def prepare_source(self, event: Event, source: dict) -> Prepared:
        payload = event.payload
        common = {
            "sourceId": payload["sourceId"],
            "sourceVersionId": payload["sourceVersionId"],
            "notebookId": str(source["notebook_id"]),
        }
        if event.event_type == "source.delete.requested.v1":
            async with self.pool.connection() as conn:
                versions = await (
                    await conn.execute(
                        "SELECT id,object_key,normalized_object_key FROM source_versions WHERE source_id=%s",
                        (source["id"],),
                    )
                ).fetchall()
            for version_id, original, normalized in versions:
                keys = {
                    original,
                    normalized,
                    f"{event.workspace_id}/{source['id']}/{version_id}/normalized-v1.json",
                }
                for key in keys - {None, ""}:
                    await asyncio.to_thread(self.storage.delete, key)
            return Prepared(facts=[event.fact("source.deleted.v1", common)])
        if event.event_type in {
            "source.parse.requested.v1",
            "transcript.fetch.requested.v1",
        }:
            segments = None
            if event.event_type == "transcript.fetch.requested.v1":
                sections, segments = await fetch_transcript(
                    source["canonical_uri"],
                    payload.get("preferredLanguages", ["en", "vi"]),
                    self.settings,
                )
            else:
                if source["type"] == "WEB":
                    data = await asyncio.to_thread(
                        fetch_public_url,
                        source["canonical_uri"],
                        self.settings.max_web_bytes,
                    )
                else:
                    data = await asyncio.to_thread(
                        self.storage.read, source["object_key"]
                    )
                    if (
                        source["size_bytes"] is not None
                        and len(data) != source["size_bytes"]
                    ):
                        raise PipelineError(
                            "SOURCE_SIZE_MISMATCH",
                            "Uploaded source size does not match its verified metadata.",
                        )
                    checksum = source["checksum_sha256"]
                    if (
                        checksum
                        and hashlib.sha256(data).hexdigest() != checksum.strip()
                    ):
                        raise PipelineError(
                            "SOURCE_CHECKSUM_MISMATCH",
                            "Uploaded source checksum does not match its verified metadata.",
                        )
                sections = await asyncio.to_thread(
                    parse_source, data, source["type"], self.settings.max_pdf_pages
                )
            key = self.normalized_key(event)
            normalized_data = json.dumps(normalized_payload(sections), ensure_ascii=False).encode()
            facts = [
                event.fact(
                    "source.parsed.v1",
                    {
                        **common,
                        "normalizedObjectKey": key,
                        "parserVersion": PARSER_VERSION,
                        "sectionCount": len(sections),
                    },
                )
            ]
            if segments:
                facts.append(
                    event.fact(
                        "source.transcript.ready.v1",
                        {
                            **common,
                            "transcriptSegmentCount": len(segments),
                            "normalizedObjectKey": key,
                        },
                    )
                )
            return Prepared(facts=facts, sections=sections, segments=segments, normalized_data=normalized_data)
        if event.event_type == "source.parsed.v1":
            key = self.normalized_key(event)
            if payload.get("normalizedObjectKey") != key:
                raise PipelineError(
                    "EVENT_SCOPE_INVALID",
                    "Normalized object reference does not match the scoped source version.",
                )
            data = await asyncio.to_thread(self.storage.read, key)
            try:
                normalized = json.loads(data)
                if normalized.get("parserVersion") != PARSER_VERSION:
                    raise ValueError("Unsupported normalized format")
                sections = [Section(**s) for s in normalized["sections"]]
                if (
                    not sections
                    or len(sections) > 20000
                    or any(not isinstance(s.text, str) or not s.text for s in sections)
                ):
                    raise ValueError("Invalid normalized sections")
            except (KeyError, TypeError, ValueError) as exc:
                raise PipelineError(
                    "SOURCE_NORMALIZED_INVALID",
                    "Normalized source representation is invalid.",
                ) from exc
            chunks = chunk_sections(UUID(payload["sourceVersionId"]), sections)
            if len(chunks) > 15000:
                raise PipelineError(
                    "SOURCE_TOO_MANY_CHUNKS",
                    "Source exceeds the index processing limit.",
                )
            vectors = await self.provider.embed([chunk["text"] for chunk in chunks])
            return Prepared(
                facts=[
                    event.fact(
                        "source.indexed.v1",
                        {
                            **common,
                            "chunkCount": len(chunks),
                            "embeddingModel": self.provider.embedding_model,
                            "retrievalConfigVersion": RETRIEVAL_VERSION,
                        },
                    )
                ],
                sections=sections,
                chunks=chunks,
                vectors=vectors,
            )
        if event.event_type == "source.indexed.v1":
            async with self.pool.connection() as conn:
                async with conn.cursor(row_factory=dict_row) as cur:
                    await cur.execute(
                        """SELECT id,text,metadata_json AS metadata FROM document_chunks
                                       WHERE workspace_id=%s AND notebook_id=%s AND source_version_id=%s ORDER BY ordinal""",
                        (
                            event.workspace_id,
                            source["notebook_id"],
                            UUID(payload["sourceVersionId"]),
                        ),
                    )
                    chunks = await cur.fetchall()
            if not chunks:
                raise PipelineError("SOURCE_INDEX_EMPTY", "Source has no indexed text.")
            concepts = concept_candidates(chunks)
            return Prepared(
                facts=[
                    event.fact(
                        "source.ready.v1",
                        {
                            **common,
                            "chunkCount": len(chunks),
                            "conceptCount": len(concepts),
                            "concepts": concepts,
                        },
                    )
                ]
            )
        raise PipelineError(
            "EVENT_TYPE_UNSUPPORTED", "This worker does not accept the event type."
        )

    async def prepare_artifact(self, event: Event, job: dict) -> Prepared:
        kinds = {
            "quiz.generate.requested.v1": ("QUIZ", "quiz.generated.v1", "quizId"),
            "flashcards.generate.requested.v1": (
                "FLASHCARDS",
                "flashcards.generated.v1",
                "deckId",
            ),
            "study-guide.generate.requested.v1": (
                "STUDY_GUIDE",
                "study-guide.generated.v1",
                "studyGuideId",
            ),
        }
        if event.event_type not in kinds:
            raise PipelineError(
                "EVENT_TYPE_UNSUPPORTED", "Unsupported artifact command."
            )
        kind, fact_type, id_field = kinds[event.event_type]
        if kind != job["artifact_type"]:
            raise PipelineError(
                "EVENT_SCOPE_INVALID", "Artifact command type differs from its job."
            )
        try:
            context = InternalContext(
                userId=job["user_id"],
                workspaceId=event.workspace_id,
                notebookId=job["notebook_id"],
                sourceIds=job["scope_json"]["sourceIds"],
                traceId=event.trace_id,
                permissions=["artifact:generate"],
            )
        except (KeyError, ValueError) as exc:
            raise PipelineError(
                "EVENT_SCOPE_INVALID", "Artifact source scope is invalid."
            ) from exc
        evidence = build_context(
            await self.repository.sample(context, 60),
            self.settings.context_token_budget,
        )
        result = await generate_artifact(
            kind, job["options_json"], evidence, self.provider
        )
        payload = {
            "artifactJobId": str(job["id"]),
            "notebookId": str(job["notebook_id"]),
            "userId": str(job["user_id"]),
            "artifactType": kind,
            id_field: str(uuid5(job["id"], kind)),
            **result,
        }
        if kind == "QUIZ":
            payload["questionCount"] = len(result["artifact"]["questions"])
        return Prepared(facts=[event.fact(fact_type, payload)])

    async def persist_derived(
        self, conn, event: Event, scope: dict, prepared: Prepared
    ):
        version_id = UUID(event.payload["sourceVersionId"])
        if prepared.normalized_data is not None:
            # The shared source lock prevents deletion from racing a late object write.
            await asyncio.to_thread(self.storage.write, self.normalized_key(event), prepared.normalized_data)
        if event.event_type == "source.delete.requested.v1":
            params = (event.workspace_id, scope["notebook_id"], scope["id"])
            predicate = "workspace_id=%s AND notebook_id=%s AND source_version_id IN (SELECT id FROM source_versions WHERE source_id=%s)"
            await conn.execute(
                "DELETE FROM document_chunks c WHERE "
                + predicate
                + " AND NOT EXISTS (SELECT 1 FROM citations t WHERE t.chunk_id=c.id)",
                params,
            )
            await conn.execute(
                "UPDATE document_chunks SET text='[Source deleted]',embedding=NULL,metadata_json='{}'::jsonb WHERE "
                + predicate,
                params,
            )
            await conn.execute(
                "DELETE FROM transcript_segments WHERE source_version_id IN (SELECT id FROM source_versions WHERE source_id=%s)",
                (scope["id"],),
            )
            await conn.execute(
                "DELETE FROM document_sections WHERE source_version_id IN (SELECT id FROM source_versions WHERE source_id=%s)",
                (scope["id"],),
            )
            return
        for ordinal, section in enumerate(prepared.sections or []):
            await conn.execute(
                """INSERT INTO document_sections(id,source_version_id,section_key,heading,ordinal,page_start,page_end,time_start_ms,time_end_ms)
                               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(source_version_id,section_key) DO NOTHING""",
                (
                    uuid5(version_id, section.key),
                    version_id,
                    section.key,
                    section.heading,
                    ordinal,
                    section.page_no,
                    section.page_no,
                    section.start_ms,
                    section.end_ms,
                ),
            )
        for chunk, vector in zip(
            prepared.chunks or [], prepared.vectors or [], strict=True
        ):
            await conn.execute(
                """INSERT INTO document_chunks(id,workspace_id,notebook_id,source_version_id,section_id,chunk_key,ordinal,text,page_no,start_ms,end_ms,token_count,metadata_json,embedding_model,embedding)
                               VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::vector)
                               ON CONFLICT(source_version_id,chunk_key) DO UPDATE SET embedding=EXCLUDED.embedding,embedding_model=EXCLUDED.embedding_model""",
                (
                    chunk["id"],
                    event.workspace_id,
                    scope["notebook_id"],
                    version_id,
                    chunk["section_id"],
                    chunk["chunk_key"],
                    chunk["ordinal"],
                    chunk["text"],
                    chunk["page_no"],
                    chunk["start_ms"],
                    chunk["end_ms"],
                    chunk["token_count"],
                    Jsonb(chunk["metadata"]),
                    self.provider.embedding_model,
                    str(vector),
                ),
            )
        for number, segment in enumerate(prepared.segments or []):
            await conn.execute(
                """INSERT INTO transcript_segments(id,source_version_id,segment_no,start_ms,end_ms,text,language)
                               VALUES (%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(source_version_id,segment_no) DO NOTHING""",
                (
                    uuid5(version_id, f"transcript-{number}"),
                    version_id,
                    number,
                    segment.start_ms,
                    segment.end_ms,
                    segment.text,
                    segment.language,
                ),
            )

    async def record_failure(self, consumer: str, event: Event, error: PipelineError):
        # Invalid or stale scope must never publish a mutation fact against someone else's aggregate.
        if error.code in {
            "EVENT_SCHEMA_INVALID",
            "EVENT_SCOPE_INVALID",
            "SOURCE_STALE_EVENT",
            "ARTIFACT_STALE_EVENT",
        }:
            return
        artifact = event.event_type.endswith("generate.requested.v1")
        async with self.pool.connection() as conn:
            async with conn.transaction():
                await lock_event(conn, consumer, event.event_id)
                if await seen(conn, consumer, event.event_id):
                    return
                scope = await (
                    artifact_scope(conn, event, lock=True)
                    if artifact
                    else source_scope(conn, event, lock=True)
                )
                identifiers = (
                    {
                        "artifactJobId": str(scope["id"]),
                        "notebookId": str(scope["notebook_id"]),
                        "userId": str(scope["user_id"]),
                    }
                    if artifact
                    else {
                        "sourceId": event.payload["sourceId"],
                        "sourceVersionId": event.payload["sourceVersionId"],
                        "notebookId": str(scope["notebook_id"]),
                        "stage": event.event_type,
                    }
                )
                fact = event.fact(
                    "artifact.generation.failed.v1"
                    if artifact
                    else "source.processing.failed.v1",
                    {
                        **identifiers,
                        "errorCode": error.code,
                        "safeDetail": error.safe_detail,
                        "retryable": error.retryable,
                    },
                )
                await finish_event(conn, consumer, event, [fact])
