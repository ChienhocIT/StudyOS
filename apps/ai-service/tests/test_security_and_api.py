import json
import time
from uuid import uuid4

import jwt
import pytest
from app.main import create_app
from fastapi.testclient import TestClient
from studyos_ai.config import Settings
from studyos_ai.contracts import InternalContext
from studyos_ai.providers import LocalExtractiveProvider
from studyos_ai.retrieval import Chunk
from studyos_ai.security import sign_internal_token


@pytest.fixture
def settings():
    return Settings(
        _env_file=None,
        internal_jwt_secret="test-secret-only-at-least-thirty-two-characters",
        model_provider="local-extractive",
    )


@pytest.fixture
def context():
    return InternalContext(
        userId=uuid4(),
        workspaceId=uuid4(),
        notebookId=uuid4(),
        sourceIds=[uuid4()],
        traceId="test-trace-001",
        permissions=[
            "chat:generate",
            "artifact:generate",
            "language:analyze",
            "retrieval:debug",
        ],
    )


class ScopedRepository:
    def __init__(self, context):
        self.context = context
        self.calls = 0

    async def search(self, context, query, vector, model, limit):
        assert context == self.context
        self.calls += 1
        return [
            Chunk(
                str(uuid4()),
                str(context.source_ids[0]),
                str(uuid4()),
                "Photosynthesis converts sunlight into chemical energy stored in glucose.",
                "Biology",
            )
        ]

    async def sample(self, context, limit=30):
        return await self.search(context, "", [], "", limit)


def chat_body(context):
    return {
        "context": context.model_dump(mode="json", by_alias=True),
        "requestId": str(uuid4()),
        "messageId": str(uuid4()),
        "content": "Explain photosynthesis",
        "mode": "ASK",
        "history": [],
    }


@pytest.mark.parametrize(
    "field",
    ["workspaceId", "notebookId", "userId", "sourceIds", "permissions", "traceId"],
)
def test_unsigned_context_mutation_never_reaches_retrieval(settings, context, field):
    repository = ScopedRepository(context)
    body = chat_body(context)
    body["context"][field] = (
        [str(uuid4())]
        if field == "sourceIds"
        else (
            ["language:analyze"]
            if field == "permissions"
            else ("other-trace" if field == "traceId" else str(uuid4()))
        )
    )
    with TestClient(
        create_app(settings, repository, LocalExtractiveProvider())
    ) as client:
        result = client.post(
            "/internal/v1/chat/stream",
            json=body,
            headers={
                "Authorization": "Bearer " + sign_internal_token(context, settings)
            },
        )
    assert result.status_code == 401
    assert repository.calls == 0


@pytest.mark.parametrize(
    "mutation",
    [
        "expired",
        "wrong_audience",
        "wrong_issuer",
        "long_lifetime",
        "wrong_subject",
        "missing_iat",
    ],
)
def test_invalid_service_tokens_rejected(settings, context, mutation):
    token = sign_internal_token(context, settings)
    claims = jwt.decode(token, options={"verify_signature": False})
    if mutation == "expired":
        claims["iat"], claims["exp"] = int(time.time()) - 200, int(time.time()) - 1
    elif mutation == "wrong_audience":
        claims["aud"] = "studyos-web"
    elif mutation == "wrong_issuer":
        claims["iss"] = "attacker"
    elif mutation == "long_lifetime":
        claims["exp"] = claims["iat"] + 3600
    elif mutation == "wrong_subject":
        claims["sub"] = str(uuid4())
    else:
        del claims["iat"]
    bad = jwt.encode(
        claims, settings.internal_jwt_secret.get_secret_value(), algorithm="HS256"
    )
    with TestClient(
        create_app(settings, ScopedRepository(context), LocalExtractiveProvider())
    ) as client:
        result = client.post(
            "/internal/v1/chat/stream",
            json=chat_body(context),
            headers={"Authorization": "Bearer " + bad},
        )
    assert result.status_code == 401


def test_chat_stream_completion_has_only_scoped_source_provenance(settings, context):
    with TestClient(
        create_app(settings, ScopedRepository(context), LocalExtractiveProvider())
    ) as client:
        response = client.post(
            "/internal/v1/chat/stream",
            json=chat_body(context),
            headers={
                "Authorization": "Bearer " + sign_internal_token(context, settings)
            },
        )
        health = client.get("/health/ready").json()
    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    completion = events[-1]
    assert completion["type"] == "assistant.completed"
    payload = completion["payload"]
    assert payload["groundingStatus"] == "SUPPORTED"
    assert payload["citations"][0]["sourceId"] == str(context.source_ids[0])
    assert (
        "".join(e["payload"]["delta"] for e in events if e["type"] == "assistant.delta")
        == payload["content"]
    )
    assert health["capabilities"]["translation"] is False


def test_empty_scope_does_not_broaden_retrieval(settings, context):
    context.source_ids = []
    repo = ScopedRepository(context)
    with TestClient(create_app(settings, repo, LocalExtractiveProvider())) as client:
        response = client.post(
            "/internal/v1/chat/stream",
            json=chat_body(context),
            headers={
                "Authorization": "Bearer " + sign_internal_token(context, settings)
            },
        )
    final = json.loads(response.text.splitlines()[-1])["payload"]
    assert final["groundingStatus"] == "INSUFFICIENT"
    assert final["citations"] == []
    assert repo.calls == 0


def test_local_language_declares_missing_capability(settings, context):
    with TestClient(
        create_app(settings, ScopedRepository(context), LocalExtractiveProvider())
    ) as client:
        result = client.post(
            "/internal/v1/language/analyze",
            headers={
                "Authorization": "Bearer " + sign_internal_token(context, settings)
            },
            json={
                "context": context.model_dump(mode="json", by_alias=True),
                "sentence": "Hello, world.",
            },
        )
    assert result.status_code == 503
    assert result.json()["code"] == "AI_CAPABILITY_UNAVAILABLE"


def test_debug_disabled_and_missing_permission_denied(settings, context):
    with TestClient(
        create_app(settings, ScopedRepository(context), LocalExtractiveProvider())
    ) as client:
        result = client.post(
            "/internal/v1/retrieval/debug",
            headers={
                "Authorization": "Bearer " + sign_internal_token(context, settings)
            },
            json={
                "context": context.model_dump(mode="json", by_alias=True),
                "query": "test",
            },
        )
    assert result.status_code == 404


def test_production_rejects_development_provider():
    with pytest.raises(ValueError, match="not permitted in production"):
        Settings(
            _env_file=None,
            internal_jwt_secret="test-secret-only-at-least-thirty-two-characters",
            environment="production",
            model_provider="local-extractive",
        )
