import asyncio
import json
import logging
from dataclasses import dataclass

import aio_pika
import psycopg
from psycopg.rows import dict_row
from pydantic import ValidationError
from studyos_ai.errors import PipelineError
from studyos_ai.telemetry import WORKER_EVENTS

from .events import Event

LOGGER = logging.getLogger("studyos.worker")
RETRY_DELAYS = (10000, 60000, 300000, 1800000)


@dataclass(frozen=True)
class QueueSpec:
    name: str
    exchange: str
    routing_key: str


QUEUES = [
    QueueSpec(
        "q.source.parse.v1", "studyos.source.commands", "source.parse.requested.v1"
    ),
    QueueSpec("q.source.index.v1", "studyos.source.events", "source.parsed.v1"),
    QueueSpec("q.source.enrich.v1", "studyos.source.events", "source.indexed.v1"),
    QueueSpec(
        "q.source.transcript.v1",
        "studyos.source.commands",
        "transcript.fetch.requested.v1",
    ),
    QueueSpec(
        "q.source.delete.v1", "studyos.source.commands", "source.delete.requested.v1"
    ),
    QueueSpec(
        "q.artifact.quiz.v1", "studyos.artifact.commands", "quiz.generate.requested.v1"
    ),
    QueueSpec(
        "q.artifact.flashcards.v1",
        "studyos.artifact.commands",
        "flashcards.generate.requested.v1",
    ),
    QueueSpec(
        "q.artifact.study-guide.v1",
        "studyos.artifact.commands",
        "study-guide.generate.requested.v1",
    ),
]


def retry_delay(attempt: int, retryable: bool) -> int | None:
    return (
        RETRY_DELAYS[attempt]
        if retryable and 0 <= attempt < len(RETRY_DELAYS)
        else None
    )


def classify_error(exc: Exception) -> PipelineError:
    if isinstance(exc, PipelineError):
        return exc
    if isinstance(
        exc,
        (
            psycopg.OperationalError,
            psycopg.InterfaceError,
            TimeoutError,
            ConnectionError,
        ),
    ):
        return PipelineError(
            "WORKER_DEPENDENCY_UNAVAILABLE",
            "A processing dependency is temporarily unavailable.",
            True,
        )
    if isinstance(exc, (ValidationError, ValueError, TypeError, KeyError)):
        return PipelineError(
            "EVENT_SCHEMA_INVALID", "The processing message is invalid."
        )
    return PipelineError(
        "WORKER_PROCESSING_FAILED", "Source processing encountered an internal error."
    )


async def declare_topology(channel):
    exchanges = {}
    for name in {q.exchange for q in QUEUES} | {
        "studyos.artifact.events",
        "studyos.retry",
        "studyos.dlx",
    }:
        exchanges[name] = await channel.declare_exchange(
            name, aio_pika.ExchangeType.TOPIC, durable=True
        )
    queues = {}
    for spec in QUEUES:
        queue = await channel.declare_queue(
            spec.name,
            durable=True,
            arguments={
                "x-dead-letter-exchange": "studyos.dlx",
                "x-dead-letter-routing-key": spec.name,
            },
        )
        await queue.bind(exchanges[spec.exchange], routing_key=spec.routing_key)
        dead = await channel.declare_queue(spec.name + ".dead", durable=True)
        await dead.bind(exchanges["studyos.dlx"], routing_key=spec.name)
        for delay in RETRY_DELAYS:
            retry_name = f"{spec.name}.retry.{delay}"
            # Default-exchange return targets only this queue, avoiding retry event fan-out.
            retry = await channel.declare_queue(
                retry_name,
                durable=True,
                arguments={
                    "x-message-ttl": delay,
                    "x-dead-letter-exchange": "",
                    "x-dead-letter-routing-key": spec.name,
                },
            )
            await retry.bind(exchanges["studyos.retry"], routing_key=retry_name)
        queues[spec.name] = queue
    return exchanges, queues


class Consumer:
    def __init__(self, pipeline, channel, semaphore):
        self.pipeline, self.channel, self.semaphore = pipeline, channel, semaphore

    async def process(self, spec: QueueSpec, message):
        async with self.semaphore:
            event = None
            try:
                if len(message.body) > 256000:
                    raise PipelineError(
                        "EVENT_PAYLOAD_TOO_LARGE",
                        "Processing message exceeds the size limit.",
                    )
                event = Event.model_validate_json(message.body)
                if event.event_type != spec.routing_key:
                    raise PipelineError(
                        "EVENT_SCHEMA_INVALID",
                        "Event type does not match the consumer queue.",
                    )
                applied = await self.pipeline.handle(spec.name, event)
                await message.ack()
                WORKER_EVENTS.labels(
                    spec.name, "success" if applied else "duplicate"
                ).inc()
            except asyncio.CancelledError:
                # Closing channel requeues unacknowledged work.
                raise
            except Exception as exc:  # noqa: BLE001 -- Broker boundary classifies poison deliveries.
                error = classify_error(exc)
                try:
                    raw_attempt = (message.headers or {}).get("x-studyos-attempt", 0)
                    attempt = (
                        raw_attempt
                        if isinstance(raw_attempt, int) and 0 <= raw_attempt <= 20
                        else len(RETRY_DELAYS)
                    )
                    delay = retry_delay(attempt, error.retryable)
                    if delay is None and event:
                        await self.pipeline.record_failure(spec.name, event, error)
                    headers = dict(message.headers or {})
                    headers.update(
                        {
                            "x-studyos-attempt": attempt + 1,
                            "x-studyos-error": error.code,
                            "x-studyos-original-queue": spec.name,
                        }
                    )
                    exchange_name = "studyos.retry" if delay else "studyos.dlx"
                    routing_key = f"{spec.name}.retry.{delay}" if delay else spec.name
                    exchange = await self.channel.get_exchange(exchange_name)
                    await exchange.publish(
                        aio_pika.Message(
                            body=message.body,
                            content_type="application/json",
                            delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                            headers=headers,
                            message_id=str(event.event_id)
                            if event
                            else message.message_id,
                        ),
                        routing_key=routing_key,
                        mandatory=True,
                        timeout=15,
                    )
                    # Original is acknowledged only after retry/DLQ publisher confirmation.
                    await message.ack()
                    WORKER_EVENTS.labels(
                        spec.name, "retry" if delay else "dead_letter"
                    ).inc()
                    LOGGER.warning(
                        json.dumps(
                            {
                                "service": "studyos-worker",
                                "queue": spec.name,
                                "errorCode": error.code,
                                "attempt": attempt,
                                "traceId": event.trace_id if event else None,
                            }
                        )
                    )
                except Exception:  # noqa: BLE001 -- Retain original delivery when handoff fails.
                    # The durable original remains available when handoff fails.
                    await message.nack(requeue=True)
                    await asyncio.sleep(1)


async def publish_outbox(pool, channel, stop: asyncio.Event):
    while not stop.is_set():
        try:
            count = 0
            async with pool.connection() as conn:
                async with conn.transaction():
                    async with conn.cursor(row_factory=dict_row) as cur:
                        await cur.execute("""SELECT * FROM worker_outbox_events WHERE published_at IS NULL
                                           ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 30""")
                        rows = await cur.fetchall()
                    for row in rows:
                        exchange = await channel.get_exchange(row["exchange_name"])
                        await exchange.publish(
                            aio_pika.Message(
                                json.dumps(row["event_json"]).encode(),
                                content_type="application/json",
                                message_id=str(row["id"]),
                                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                            ),
                            routing_key=row["routing_key"],
                            mandatory=True,
                            timeout=15,
                        )
                        await conn.execute(
                            "UPDATE worker_outbox_events SET published_at=now() WHERE id=%s",
                            (row["id"],),
                        )
                        count += 1
            if not count:
                try:
                    await asyncio.wait_for(stop.wait(), timeout=1)
                except TimeoutError:
                    pass
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 -- Unpublished durable rows survive publisher faults.
            LOGGER.warning(
                '{"service":"studyos-worker","event":"outbox_publish_retry"}'
            )
            try:
                await asyncio.wait_for(stop.wait(), timeout=3)
            except TimeoutError:
                pass
