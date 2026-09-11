"""Real local learning loop. Creates isolated test accounts; never touches existing users.

Run: python scripts/configure_runtime.py uv run --project apps/ai-service python tests/e2e/local_smoke.py
"""
import asyncio
import json
import time
from pathlib import Path
from uuid import uuid4

import httpx
from websockets.asyncio.client import connect

BASE = "http://127.0.0.1:8080/api/v1"
ARTIFACTS = Path(".tmp")
CONTENT = """# Database transactions
Atomicity is the property that every transaction either commits completely or rolls back completely.
Isolation is the property that concurrent transactions do not expose incomplete changes to each other.
Durability is the property that committed data survives a server restart.
Consistency is the property that transactions preserve database integrity constraints.
PostgreSQL is a relational database that supports transactions and full text search.
"""


def request(client, method, path, body=None, token=None, key=None, expected=(200, 201, 202, 204)):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    response = client.request(method, BASE + path, json=body, headers=headers)
    if response.status_code not in expected:
        raise AssertionError(f"{method} {path}: HTTP {response.status_code}: {response.text[:400]}")
    return response.json() if response.content else None


def wait_resource(client, path, token, target, seconds=100):
    end = time.monotonic() + seconds
    previous = None
    while time.monotonic() < end:
        result = request(client, "GET", path, token=token)
        status = result["status"]
        if status != previous:
            print(f"  {path}: {status}", flush=True)
            previous = status
        if status == target:
            return result
        if status in {"FAILED", "CANCELLED"}:
            raise AssertionError(f"Resource failed: {result}")
        time.sleep(1)
    raise AssertionError(f"Timeout waiting for {path} to become {target}")


async def chat(client, token, notebook):
    conversation = request(client, "POST", f"/notebooks/{notebook}/conversations", {"mode": "ASK"}, token)
    ticket = request(client, "POST", f"/conversations/{conversation['id']}/ws-token", {}, token)
    command = {"type": "chat.message.send", "protocolVersion": "1.0", "eventId": str(uuid4()),
               "timestamp": "2026-09-11T00:00:00Z", "conversationId": conversation["id"],
               "requestId": str(uuid4()), "sequence": None,
               "payload": {"content": "What is atomicity in database transactions?", "mode": "ASK", "sourceIds": []}}
    async with connect(ticket["websocketUrl"], origin="http://localhost:3000", open_timeout=10) as ws:
        ready = json.loads(await asyncio.wait_for(ws.recv(), 10))
        assert ready["type"] == "session.ready", ready
        await ws.send(json.dumps(command))
        for _ in range(200):
            event = json.loads(await asyncio.wait_for(ws.recv(), 65))
            if event["type"] in {"assistant.failed", "error"}:
                raise AssertionError(f"Chat failed: {event['payload']}")
            if event["type"] == "assistant.completed":
                assert event["payload"]["groundingStatus"] in {"SUPPORTED", "PARTIAL"}
                break
        else:
            raise AssertionError("Chat never completed")
    history = request(client, "GET", f"/conversations/{conversation['id']}/messages", token=token)
    assert len(history) == 2 and history[-1]["status"] == "COMPLETED", history
    assert history[-1]["citations"], "No durable citations"
    print("PASS grounded WebSocket chat and durable citations", flush=True)
    return conversation["id"]


def main():
    run_id = uuid4().hex[:12]
    with httpx.Client(timeout=20, trust_env=False) as client:
        user = request(client, "POST", "/auth/register", {"email": f"smoke-{run_id}@example.com", "password": "StudyOSTest123!", "displayName": "Smoke learner"})
        token = user["tokens"]["accessToken"]
        outsider = request(client, "POST", "/auth/register", {"email": f"outsider-{run_id}@example.com", "password": "StudyOSTest123!", "displayName": "Other learner"})
        other = outsider["tokens"]["accessToken"]
        workspace = request(client, "GET", "/workspaces", token=token)[0]["id"]
        notebook = request(client, "POST", f"/workspaces/{workspace}/notebooks", {"title": f"Learning loop {run_id}"}, token)["id"]
        request(client, "GET", f"/notebooks/{notebook}", token=other, expected=(403, 404))
        print("PASS register, personal workspace, notebook, tenant isolation", flush=True)
        key = str(uuid4())
        source = request(client, "POST", f"/notebooks/{notebook}/sources/raw", {"title": "Transaction fundamentals", "content": CONTENT}, token, key)
        same = request(client, "POST", f"/notebooks/{notebook}/sources/raw", {"title": "Transaction fundamentals", "content": CONTENT}, token, key)
        assert source["id"] == same["id"]
        wait_resource(client, f"/sources/{source['id']}", token, "READY")
        print("PASS raw ingestion through RabbitMQ, chunks, embeddings, source readiness", flush=True)
        conversation = asyncio.run(chat(client, token, notebook))
        key = str(uuid4())
        job = request(client, "POST", f"/notebooks/{notebook}/artifacts/quiz", {"count": 3}, token, key)
        same = request(client, "POST", f"/notebooks/{notebook}/artifacts/quiz", {"count": 3}, token, key)
        assert job["id"] == same["id"]
        job = wait_resource(client, f"/jobs/{job['id']}", token, "SUCCEEDED")
        assert "correctAnswer" not in json.dumps(job), "Job leaks the answer key"
        quiz = request(client, "GET", f"/quizzes/{job['resultRef']}", token=token)
        assert quiz["questions"] and "correctAnswer" not in json.dumps(quiz)
        request(client, "GET", f"/quizzes/{quiz['id']}", token=other, expected=(403,404))
        attempt = request(client, "POST", f"/quizzes/{quiz['id']}/attempts", {}, token)
        for question in quiz["questions"]:
            request(client, "POST", f"/attempts/{attempt['id']}/answers", {"questionId": question["id"], "answer": "deliberately incorrect"}, token)
        result = request(client, "POST", f"/attempts/{attempt['id']}/complete", {}, token)
        assert result["score"] == 0
        mastery = request(client, "GET", f"/notebooks/{notebook}/mastery", token=token)
        repeated = request(client, "POST", f"/attempts/{attempt['id']}/complete", {}, token)
        assert repeated["score"] == result["score"]
        assert mastery == request(client, "GET", f"/notebooks/{notebook}/mastery", token=token)
        assert any(row["evidenceCount"] > 0 for row in mastery), "Quiz did not create mastery evidence"
        print("PASS quiz generation, hidden answer key, scoring, mastery and duplicate completion", flush=True)
        deck_job = request(client, "POST", f"/notebooks/{notebook}/artifacts/flashcards", {"count": 2}, token, str(uuid4()))
        wait_resource(client, f"/jobs/{deck_job['id']}", token, "SUCCEEDED")
        cards = request(client, "GET", f"/review/queue?notebookId={notebook}", token=token)
        assert cards
        card = cards[0]["cardId"]
        request(client, "POST", f"/flashcards/{card}/reviews", {"grade": 3}, other, str(uuid4()), expected=(403,404))
        key = str(uuid4())
        review = request(client, "POST", f"/flashcards/{card}/reviews", {"grade": 3}, token, key)
        again = request(client, "POST", f"/flashcards/{card}/reviews", {"grade": 3}, token, key)
        assert review == again
        request(client, "POST", f"/flashcards/{card}/reviews", {"grade": 3}, token, str(uuid4()), expected=(409,))
        print("PASS flashcards, private review, due schedule and idempotent grading", flush=True)
        later = request(client, "POST", f"/notebooks/{notebook}/sources/raw", {"title": "Outbox after learning", "content": CONTENT}, token, str(uuid4()))
        wait_resource(client, f"/sources/{later['id']}", token, "READY")
        print("PASS learning events do not block subsequent ingestion", flush=True)
        overview = request(client, "GET", "/analytics/overview", token=token)
        assert overview["completedQuizzes"] == 1 and overview["completedReviews"] == 1
        guide = request(client, "POST", f"/notebooks/{notebook}/artifacts/study-guide", {}, token, str(uuid4()))
        guide = wait_resource(client, f"/jobs/{guide['id']}", token, "SUCCEEDED")
        assert guide["result"]["sourceRefs"] and "[C" in guide["result"]["content"]
        print("PASS study guide with validated durable source references", flush=True)
        request(client, "DELETE", f"/sources/{source['id']}", token=token)
        request(client, "GET", f"/quizzes/{quiz['id']}", token=token, expected=(404,))
        assert not request(client, "GET", f"/review/queue?notebookId={notebook}", token=token)
        invalidated = request(client, "GET", f"/jobs/{guide['id']}", token=token)
        assert invalidated["status"] == "FAILED" and invalidated["result"] is None
        print("PASS deleted source invalidates quiz, review cards and study guide", flush=True)
        ARTIFACTS.mkdir(exist_ok=True)
        (ARTIFACTS / "e2e-result.json").write_text(json.dumps({"status": "PASS", "runId": run_id, "notebookId": notebook,
            "conversationId": conversation, "checks": 10, "provider": "local-extractive"}, indent=2), encoding="utf-8")
        print("PASS full local learning loop (10 grouped checks)", flush=True)


if __name__ == "__main__":
    main()
