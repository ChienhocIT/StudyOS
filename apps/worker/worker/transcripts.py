from urllib.parse import parse_qs, urlsplit

import httpx
from pydantic import Field, TypeAdapter, ValidationError
from studyos_ai.config import Settings
from studyos_ai.contracts import Contract
from studyos_ai.errors import PipelineError
from studyos_ai.ingestion import Section, normalize_text


class Segment(Contract):
    start_ms: int = Field(ge=0, le=86400000)
    end_ms: int = Field(ge=0, le=86400000)
    text: str = Field(min_length=1, max_length=10000)
    language: str | None = Field(default=None, max_length=16)


def youtube_video_id(uri: str) -> str:
    parsed = urlsplit(uri)
    if (
        parsed.scheme != "https"
        or parsed.username
        or parsed.password
        or parsed.port not in {None, 443}
    ):
        raise PipelineError(
            "SOURCE_URL_BLOCKED", "A supported HTTPS YouTube URL is required."
        )
    if (
        parsed.hostname in {"youtube.com", "www.youtube.com", "m.youtube.com"}
        and parsed.path == "/watch"
    ):
        video = parse_qs(parsed.query).get("v", [""])[0]
    elif parsed.hostname == "youtu.be":
        video = parsed.path.removeprefix("/")
    else:
        raise PipelineError(
            "SOURCE_URL_BLOCKED", "A supported YouTube video URL is required."
        )
    if len(video) != 11 or any(
        c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
        for c in video
    ):
        raise PipelineError(
            "SOURCE_URL_INVALID", "YouTube video identifier is invalid."
        )
    return video


async def fetch_transcript(
    uri: str, languages: list[str], settings: Settings
) -> tuple[list[Section], list[Segment]]:
    video = youtube_video_id(uri)
    if not settings.transcript_provider_url:
        raise PipelineError(
            "TRANSCRIPT_UNAVAILABLE",
            "No permitted transcript provider is configured. Add a manual text source instead.",
        )
    # Endpoint is operator configuration; user input is a validated video ID, never an outbound URL.
    try:
        async with httpx.AsyncClient(
            timeout=30, follow_redirects=False, trust_env=False
        ) as client:
            async with client.stream(
                "POST",
                settings.transcript_provider_url,
                headers={
                    "Authorization": "Bearer "
                    + settings.transcript_provider_key.get_secret_value()
                },
                json={"videoId": video, "preferredLanguages": languages[:5]},
            ) as response:
                if response.status_code in {403, 404}:
                    raise PipelineError(
                        "TRANSCRIPT_UNAVAILABLE",
                        "A permitted transcript is unavailable for this video.",
                    )
                if response.status_code == 429 or response.status_code >= 500:
                    raise PipelineError(
                        "TRANSCRIPT_PROVIDER_UNAVAILABLE",
                        "Transcript provider is temporarily unavailable.",
                        True,
                    )
                if response.status_code != 200:
                    raise PipelineError(
                        "TRANSCRIPT_PROVIDER_REJECTED",
                        "Transcript provider rejected this video request.",
                    )
                data = bytearray()
                async for part in response.aiter_bytes():
                    data.extend(part)
                    if len(data) > 5000000:
                        raise PipelineError(
                            "TRANSCRIPT_TOO_LARGE",
                            "Transcript exceeds the supported limit.",
                        )
        segments = TypeAdapter(list[Segment]).validate_json(data)
        if (
            not segments
            or len(segments) > 20000
            or any(s.end_ms < s.start_ms for s in segments)
        ):
            raise ValueError("Invalid transcript bounds")
        if any(a.start_ms > b.start_ms for a, b in zip(segments, segments[1:])):
            raise ValueError("Transcript must be time ordered")
        return [
            Section(
                f"segment-{i}",
                normalize_text(s.text),
                start_ms=s.start_ms,
                end_ms=s.end_ms,
            )
            for i, s in enumerate(segments)
        ], segments
    except httpx.RequestError as exc:
        raise PipelineError(
            "TRANSCRIPT_PROVIDER_UNAVAILABLE",
            "Transcript provider could not be reached.",
            True,
        ) from exc
    except (ValidationError, ValueError) as exc:
        raise PipelineError(
            "TRANSCRIPT_OUTPUT_INVALID",
            "Transcript provider returned invalid timestamped content.",
        ) from exc
