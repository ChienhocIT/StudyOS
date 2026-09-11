import asyncio
from datetime import datetime, timezone
from unittest.mock import AsyncMock
from uuid import uuid4

import pytest
from studyos_ai.errors import PipelineError
from worker.events import Event
from worker.messaging import QUEUES, RETRY_DELAYS, Consumer, retry_delay


def event():
    identifier = uuid4()
    return Event(
        eventId=uuid4(),
        eventType="source.parse.requested.v1",
        eventVersion=1,
        occurredAt=datetime.now(timezone.utc),
        producer="studyos-core",
        traceId="test-trace",
        correlationId=str(identifier),
        workspaceId=uuid4(),
        aggregateType="SOURCE",
        aggregateId=identifier,
        payload={"sourceId": str(identifier), "sourceVersionId": str(uuid4())},
    )


@pytest.mark.parametrize(
    "attempt,expected",
    [(0, 10000), (1, 60000), (2, 300000), (3, 1800000), (4, None), (-1, None)],
)
def test_retry_schedule_is_bounded(attempt, expected):
    assert retry_delay(attempt, True) == expected
    assert retry_delay(attempt, False) is None


def test_derived_fact_identity_stable_across_redelivery():
    command = event()
    assert (
        command.fact("source.parsed.v1", {}).event_id
        == command.fact("source.parsed.v1", {}).event_id
    )
    assert (
        command.fact("source.parsed.v1", {}).event_id
        != command.fact("source.ready.v1", {}).event_id
    )


async def test_duplicate_success_acknowledged_without_publish():
    pipeline, channel, message = AsyncMock(), AsyncMock(), AsyncMock()
    message.body, message.headers = event().model_dump_json(by_alias=True).encode(), {}
    pipeline.handle.return_value = False
    await Consumer(pipeline, channel, asyncio.Semaphore(1)).process(QUEUES[0], message)
    message.ack.assert_awaited_once()
    channel.get_exchange.assert_not_awaited()


async def test_retry_publish_confirm_occurs_before_original_ack():
    pipeline, channel, message = AsyncMock(), AsyncMock(), AsyncMock()
    message.body, message.headers = event().model_dump_json(by_alias=True).encode(), {}
    pipeline.handle.side_effect = PipelineError("TEMP", "temporary", True)
    exchange = AsyncMock()
    channel.get_exchange.return_value = exchange
    order = []
    exchange.publish.side_effect = lambda *a, **k: order.append("confirmed")
    message.ack.side_effect = lambda *a, **k: order.append("acked")
    await Consumer(pipeline, channel, asyncio.Semaphore(1)).process(QUEUES[0], message)
    assert order == ["confirmed", "acked"]
    pipeline.record_failure.assert_not_awaited()
    assert exchange.publish.call_args.kwargs["routing_key"].endswith("retry.10000")


async def test_poison_message_routes_dlq_and_records_safe_terminal_fact():
    pipeline, channel, message = AsyncMock(), AsyncMock(), AsyncMock()
    message.body, message.headers = (
        event().model_dump_json(by_alias=True).encode(),
        {"x-studyos-attempt": len(RETRY_DELAYS)},
    )
    pipeline.handle.side_effect = PipelineError(
        "PROVIDER_DOWN", "Provider unavailable.", True
    )
    await Consumer(pipeline, channel, asyncio.Semaphore(1)).process(QUEUES[0], message)
    pipeline.record_failure.assert_awaited_once()
    channel.get_exchange.assert_awaited_with("studyos.dlx")
    message.ack.assert_awaited_once()


async def test_failed_handoff_never_acknowledges_original(monkeypatch):
    pipeline, channel, message = AsyncMock(), AsyncMock(), AsyncMock()
    message.body, message.headers = event().model_dump_json(by_alias=True).encode(), {}
    pipeline.handle.side_effect = PipelineError("TEMP", "temporary", True)
    channel.get_exchange.side_effect = ConnectionError("broker lost")
    monkeypatch.setattr("worker.messaging.asyncio.sleep", AsyncMock())
    await Consumer(pipeline, channel, asyncio.Semaphore(1)).process(QUEUES[0], message)
    message.ack.assert_not_awaited()
    message.nack.assert_awaited_once_with(requeue=True)
