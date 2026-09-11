import json
from uuid import UUID

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from studyos_ai.errors import PipelineError

from .events import Event, exchange_for


async def seen(conn, consumer: str, event_id: UUID) -> bool:
    row = await (
        await conn.execute(
            "SELECT 1 FROM processed_events WHERE consumer_name=%s AND event_id=%s",
            (consumer, event_id),
        )
    ).fetchone()
    return row is not None


async def lock_event(conn, consumer: str, event_id: UUID):
    # Unique event advisory lock serializes duplicate deliveries before any derived mutation.
    await conn.execute(
        "SELECT pg_advisory_xact_lock(hashtextextended(%s,0))",
        (consumer + ":" + str(event_id),),
    )


async def finish_event(conn, consumer: str, event: Event, facts: list[Event]):
    for fact in facts:
        encoded = fact.model_dump(mode="json", by_alias=True)
        if len(json.dumps(encoded).encode()) > 256000:
            raise PipelineError(
                "EVENT_PAYLOAD_TOO_LARGE",
                "Generated event exceeded the broker payload limit.",
            )
        await conn.execute(
            """INSERT INTO worker_outbox_events(id,event_json,exchange_name,routing_key)
                           VALUES (%s,%s,%s,%s) ON CONFLICT(id) DO NOTHING""",
            (
                fact.event_id,
                Jsonb(encoded),
                exchange_for(fact.event_type),
                fact.event_type,
            ),
        )
    await conn.execute(
        "INSERT INTO processed_events(consumer_name,event_id) VALUES (%s,%s)",
        (consumer, event.event_id),
    )


async def source_scope(conn, event: Event, *, lock: bool = False) -> dict:
    try:
        source_id = UUID(event.payload["sourceId"])
        version_id = UUID(event.payload["sourceVersionId"])
    except (ValueError, TypeError, KeyError) as exc:
        raise PipelineError(
            "EVENT_SCHEMA_INVALID", "Source command has invalid identifiers."
        ) from exc
    if event.aggregate_id != source_id:
        raise PipelineError(
            "EVENT_SCOPE_INVALID", "Source command scope does not match its aggregate."
        )
    async with conn.cursor(row_factory=dict_row) as cur:
        await cur.execute(
            """SELECT s.id,s.notebook_id,s.workspace_id,s.type,s.canonical_uri,s.status,
                            s.current_version_id,v.object_key,v.checksum_sha256,v.mime_type,v.size_bytes
                            FROM sources s JOIN source_versions v ON v.source_id=s.id AND v.id=s.current_version_id
                            JOIN notebooks n ON n.id=s.notebook_id AND n.workspace_id=s.workspace_id
                            WHERE s.id=%s AND s.workspace_id=%s AND v.id=%s AND n.status='ACTIVE'"""
            + (" FOR SHARE OF s,v,n" if lock else ""),
            (source_id, event.workspace_id, version_id),
        )
        row = await cur.fetchone()
    deleting = event.event_type == "source.delete.requested.v1"
    if not row or (
        row["status"] != "DELETING"
        if deleting
        else row["status"] in {"DELETING", "DELETED", "FAILED"}
    ):
        raise PipelineError(
            "SOURCE_STALE_EVENT",
            "Source command is stale or outside its permitted scope.",
        )
    if (
        event.payload.get("notebookId")
        and str(row["notebook_id"]) != event.payload["notebookId"]
    ):
        raise PipelineError(
            "EVENT_SCOPE_INVALID",
            "Source command notebook does not match its stored scope.",
        )
    for field, column in (
        ("objectKey", "object_key"),
        ("canonicalUri", "canonical_uri"),
        ("sourceType", "type"),
    ):
        if field in event.payload and event.payload[field] != row[column]:
            raise PipelineError(
                "EVENT_SCOPE_INVALID",
                "Source command does not match its stored source metadata.",
            )
    return row


async def artifact_scope(conn, event: Event, *, lock: bool = False) -> dict:
    try:
        job_id = UUID(event.payload["artifactJobId"])
        user_id = UUID(event.payload["userId"])
        notebook_id = UUID(event.payload["notebookId"])
    except (ValueError, TypeError, KeyError) as exc:
        raise PipelineError(
            "EVENT_SCHEMA_INVALID", "Artifact command has invalid identifiers."
        ) from exc
    if event.aggregate_id != job_id:
        raise PipelineError(
            "EVENT_SCOPE_INVALID", "Artifact command aggregate does not match its job."
        )
    async with conn.cursor(row_factory=dict_row) as cur:
        await cur.execute(
            """SELECT j.*,n.workspace_id FROM artifact_jobs j
                             JOIN notebooks n ON n.id=j.notebook_id
                             JOIN workspace_members m ON m.workspace_id=n.workspace_id AND m.user_id=j.user_id
                             WHERE j.id=%s AND j.user_id=%s AND j.notebook_id=%s AND n.workspace_id=%s AND n.status='ACTIVE'"""
            + (" FOR SHARE OF j,n,m" if lock else ""),
            (job_id, user_id, notebook_id, event.workspace_id),
        )
        row = await cur.fetchone()
    if not row or row["status"] not in {"PENDING", "RUNNING"}:
        raise PipelineError(
            "ARTIFACT_STALE_EVENT",
            "Artifact job is stale or outside its permitted scope.",
        )
    if (
        event.payload.get("scope") != row["scope_json"]
        or event.payload.get("options", {}) != row["options_json"]
    ):
        raise PipelineError(
            "EVENT_SCOPE_INVALID",
            "Artifact command scope/options differ from its stored job.",
        )
    return row
