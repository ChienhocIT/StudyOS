import asyncio
import hashlib
import math
import re
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
            return content, {
                "model": self.model,
                "inputTokens": usage.get("prompt_tokens", 0),
                "outputTokens": usage.get("completion_tokens", 0),
            }
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise PipelineError(
                "AI_OUTPUT_INVALID", "AI provider returned invalid output."
            ) from exc


def build_provider(settings: Settings) -> ModelProvider:
    return (
        LocalExtractiveProvider()
        if settings.model_provider == "local-extractive"
        else HttpModelProvider(settings)
    )
