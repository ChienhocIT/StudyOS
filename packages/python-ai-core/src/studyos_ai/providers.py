import asyncio
import hashlib
import json
import math
import re
from collections.abc import AsyncIterator
from decimal import Decimal
from typing import Protocol

import httpx

from .config import Settings
from .errors import PipelineError


class ModelProvider(Protocol):
    model: str
    embedding_model: str

    async def embed(self, texts: list[str]) -> list[list[float]]: ...
    async def generate(
        self, messages: list[dict], *, json_output: bool = False
    ) -> tuple[str, dict]: ...
    def stream(self, messages: list[dict]) -> AsyncIterator[dict]: ...


class LocalExtractiveProvider:
    """Explicit development mode: no language model, no external facts or translation."""

    model = "local-extractive-v1"
    embedding_model = "local-hash-1536-v1"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        result = []
        for text in texts:
            values = [0.0] * 1536
            for word in re.findall(r"\w+", text.casefold()):
                digest = hashlib.sha256(word.encode()).digest()
                values[int.from_bytes(digest[:4], "big") % 1536] += (
                    1 if digest[4] % 2 else -1
                )
            norm = math.sqrt(sum(x * x for x in values)) or 1
            result.append([x / norm for x in values])
        return result

    async def generate(
        self, messages: list[dict], *, json_output: bool = False
    ) -> tuple[str, dict]:
        raise PipelineError(
            "AI_CAPABILITY_UNAVAILABLE",
            "This action requires a configured language model provider.",
        )

    def stream(self, messages: list[dict]) -> AsyncIterator[dict]:
        raise PipelineError("AI_CAPABILITY_UNAVAILABLE", "This action requires a configured language model provider.")


class HttpModelProvider:
    def __init__(self, settings: Settings):
        self.model = settings.model_name
        self.embedding_model = settings.embedding_model
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.model_provider_base_url.rstrip("/") + "/",
            timeout=settings.provider_timeout_seconds,
            headers={
                "Authorization": "Bearer "
                + settings.model_provider_api_key.get_secret_value()
            },
            follow_redirects=False,
            trust_env=False,
        )

    async def close(self):
        await self.client.aclose()

    def usage(self, data: dict) -> dict:
        input_tokens, output_tokens = data.get("prompt_tokens"), data.get("completion_tokens")
        valid = all(isinstance(n, int) and not isinstance(n, bool) and 0 <= n <= 10000000 for n in (input_tokens, output_tokens))
        result = {"model": self.model, "inputTokens": input_tokens if valid else None,
                  "outputTokens": output_tokens if valid else None, "tokenUsageKind": "provider_reported" if valid else "unavailable"}
        rates = (self.settings.model_input_usd_per_million, self.settings.model_output_usd_per_million)
        if valid and all(rate is not None for rate in rates):
            result["estimatedCostUsd"] = str((Decimal(input_tokens) * rates[0] + Decimal(output_tokens) * rates[1]) / Decimal(1000000))
        return result

    async def _post(self, path: str, data: dict) -> dict:
        for attempt in range(3):
            try:
                response = await self.client.post(path, json=data)
                if response.status_code == 429 or response.status_code >= 500:
                    raise PipelineError(
                        "AI_PROVIDER_UNAVAILABLE",
                        "AI provider is temporarily unavailable.",
                        True,
                    )
                if response.status_code >= 400:
                    raise PipelineError(
                        "AI_PROVIDER_REJECTED", "AI provider rejected the request."
                    )
                return response.json()
            except (httpx.RequestError, PipelineError) as exc:
                if isinstance(exc, PipelineError) and not exc.retryable:
                    raise
                if attempt == 2:
                    raise PipelineError(
                        "AI_PROVIDER_UNAVAILABLE",
                        "AI provider is temporarily unavailable.",
                        True,
                    ) from exc
                await asyncio.sleep(0.25 * 2**attempt)
            except ValueError as exc:
                raise PipelineError(
                    "AI_OUTPUT_INVALID", "AI provider returned invalid data."
                ) from exc
        raise AssertionError("Unreachable retry state")

    async def embed(self, texts: list[str]) -> list[list[float]]:
        result = []
        for offset in range(0, len(texts), 32):
            part = texts[offset : offset + 32]
            response = await self._post(
                "embeddings",
                {"model": self.embedding_model, "input": part, "dimensions": 1536},
            )
            try:
                rows = sorted(response["data"], key=lambda x: x["index"])
                vectors = [row["embedding"] for row in rows]
                if [row["index"] for row in rows] != list(range(len(part))):
                    raise ValueError("Missing embedding")
                if any(
                    len(v) != 1536
                    or any(
                        not isinstance(n, (int, float)) or not math.isfinite(n)
                        for n in v
                    )
                    for v in vectors
                ):
                    raise ValueError("Invalid embedding dimension/value")
                result.extend(vectors)
            except (KeyError, TypeError, ValueError) as exc:
                raise PipelineError(
                    "EMBEDDING_INVALID",
                    "Provider embeddings do not match the 1536-dimension contract.",
                ) from exc
        return result

    async def generate(
        self, messages: list[dict], *, json_output: bool = False
    ) -> tuple[str, dict]:
        data = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.1,
            "max_tokens": 4000,
        }
        if json_output:
            data["response_format"] = {"type": "json_object"}
        response = await self._post("chat/completions", data)
        try:
            content = response["choices"][0]["message"]["content"]
            if not isinstance(content, str) or not content or len(content) > 64000:
                raise ValueError("Invalid content")
            usage = response.get("usage", {})
            return content, self.usage(usage)
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise PipelineError(
                "AI_OUTPUT_INVALID", "AI provider returned invalid output."
            ) from exc

    async def stream(self, messages: list[dict]) -> AsyncIterator[dict]:
        """Forward real upstream SSE tokens; retry only before the first emitted token."""
        data = {"model": self.model, "messages": messages, "temperature": 0.1,
                "max_tokens": 4000, "stream": True, "stream_options": {"include_usage": True}}
        emitted = False
        for attempt in range(3):
            try:
                async with self.client.stream("POST", "chat/completions", json=data) as response:
                    if response.status_code == 429 or response.status_code >= 500:
                        raise PipelineError("AI_PROVIDER_UNAVAILABLE", "AI provider is temporarily unavailable.", True)
                    if response.status_code != 200:
                        raise PipelineError("AI_PROVIDER_REJECTED", "AI provider rejected the request.")
                    buffer = b""
                    content_bytes = 0
                    response_bytes = 0
                    async for part in response.aiter_bytes():
                        buffer += part
                        response_bytes += len(part)
                        if len(buffer) > 1000000 or response_bytes > 4000000:
                            raise PipelineError("AI_OUTPUT_INVALID", "AI provider stream exceeds the supported limit.")
                        while b"\n" in buffer:
                            raw_line, buffer = buffer.split(b"\n", 1)
                            line = raw_line.strip()
                            if not line.startswith(b"data:"):
                                continue
                            frame = line[5:].strip()
                            if frame == b"[DONE]":
                                return
                            payload = json.loads(frame)
                            if payload.get("error"):
                                raise PipelineError("AI_PROVIDER_UNAVAILABLE", "AI provider interrupted its response.", True)
                            usage = payload.get("usage")
                            if usage:
                                yield {"usage": self.usage(usage)}
                            choices = payload.get("choices", [])
                            if not choices:
                                continue
                            choice = choices[0]
                            if choice.get("finish_reason") in {"length", "content_filter"}:
                                raise PipelineError("AI_OUTPUT_INCOMPLETE", "AI provider could not complete a valid answer.", True)
                            delta = choice.get("delta", {}).get("content")
                            if delta:
                                if not isinstance(delta, str):
                                    raise ValueError("Invalid delta")
                                content_bytes += len(delta.encode())
                                if content_bytes > 64000:
                                    raise PipelineError("AI_OUTPUT_INVALID", "AI answer exceeds the supported limit.")
                                emitted = True
                                yield {"delta": delta}
                    raise PipelineError("AI_STREAM_INTERRUPTED", "AI provider stream ended before completion.", True)
            except (httpx.RequestError, PipelineError) as exc:
                if isinstance(exc, PipelineError) and not exc.retryable:
                    raise
                if emitted or attempt == 2:
                    raise PipelineError("AI_STREAM_INTERRUPTED", "AI provider stream was interrupted.", True) from exc
                await asyncio.sleep(0.25 * 2**attempt)
            except (ValueError, TypeError, KeyError, IndexError) as exc:
                raise PipelineError("AI_OUTPUT_INVALID", "AI provider returned an invalid stream.") from exc


def build_provider(settings: Settings) -> ModelProvider:
    return (
        LocalExtractiveProvider()
        if settings.model_provider == "local-extractive"
        else HttpModelProvider(settings)
    )
