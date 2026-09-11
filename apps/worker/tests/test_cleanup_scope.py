from datetime import datetime, timezone
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import pytest
from studyos_ai.errors import PipelineError
from worker.events import Event
from worker.repository import source_scope


def scope_fixture(event_type, notebook_status, workspace_status, status="DELETING"):
    source, version, notebook, workspace = uuid4(), uuid4(), uuid4(), uuid4()
    event = Event(
        eventId=uuid4(), eventType=event_type, eventVersion=1,
        occurredAt=datetime.now(timezone.utc), producer="studyos-core", traceId="test",
        correlationId=str(source), workspaceId=workspace, aggregateType="Source",
        aggregateId=source, payload={"sourceId": str(source), "sourceVersionId": str(version),
                                    "notebookId": str(notebook), "objectKey": "private/original"},
    )
    row = {"id": source, "current_version_id": version, "notebook_id": notebook,
           "workspace_id": workspace, "status": status, "object_key": "private/original",
           "notebook_status": notebook_status, "workspace_status": workspace_status}
    cursor = AsyncMock()
    cursor.fetchone.return_value = row
    connection = Mock()
    connection.cursor.return_value.__aenter__ = AsyncMock(return_value=cursor)
    connection.cursor.return_value.__aexit__ = AsyncMock(return_value=False)
    return connection, event, row


@pytest.mark.parametrize("notebook_status,workspace_status", [
    ("DELETED", "ACTIVE"), ("ARCHIVED", "DELETED"), ("ACTIVE", "DELETED"),
])
async def test_cleanup_survives_parent_deletion(notebook_status, workspace_status):
    conn, event, row = scope_fixture("source.delete.requested.v1", notebook_status, workspace_status)
    assert await source_scope(conn, event, lock=True) == row


@pytest.mark.parametrize("notebook_status,workspace_status", [
    ("DELETED", "ACTIVE"), ("ARCHIVED", "ACTIVE"), ("ACTIVE", "DELETED"),
])
async def test_normal_ingestion_cannot_bypass_inactive_parent(notebook_status, workspace_status):
    conn, event, _ = scope_fixture("source.parse.requested.v1", notebook_status, workspace_status, "QUEUED")
    with pytest.raises(PipelineError, match="SOURCE_STALE_EVENT"):
        await source_scope(conn, event)


async def test_cleanup_still_requires_deleting_source_and_matching_notebook():
    conn, event, row = scope_fixture("source.delete.requested.v1", "DELETED", "DELETED", "READY")
    with pytest.raises(PipelineError, match="SOURCE_STALE_EVENT"):
        await source_scope(conn, event)
    row["status"] = "DELETING"
    event.payload["notebookId"] = str(uuid4())
    with pytest.raises(PipelineError, match="EVENT_SCOPE_INVALID"):
        await source_scope(conn, event)
