from datetime import datetime, timezone
from typing import Literal
from uuid import UUID, uuid5

from pydantic import Field
from studyos_ai.contracts import Contract


class Event(Contract):
    event_id: UUID
    event_type: str = Field(pattern=r"^[a-z][a-z.-]+\.v1$", max_length=120)
    event_version: Literal[1]
    occurred_at: datetime
    producer: str = Field(min_length=1, max_length=120)
    trace_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    correlation_id: str = Field(min_length=1, max_length=160)
    causation_id: str | None = None
    workspace_id: UUID
    aggregate_type: str = Field(min_length=1, max_length=80)
    aggregate_id: UUID
    payload: dict

    def fact(self, event_type: str, payload: dict):
        return Event(
            eventId=uuid5(self.event_id, event_type),
            eventType=event_type,
            eventVersion=1,
            occurredAt=datetime.now(timezone.utc),
            producer="studyos-worker",
            traceId=self.trace_id,
            correlationId=self.correlation_id,
            causationId=str(self.event_id),
            workspaceId=self.workspace_id,
            aggregateType=self.aggregate_type,
            aggregateId=self.aggregate_id,
            payload=payload,
        )


def exchange_for(event_type: str) -> str:
    return (
        "studyos.source.events"
        if event_type.startswith("source.")
        else "studyos.artifact.events"
    )
