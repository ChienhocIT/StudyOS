import io
import socket
from uuid import uuid4

import pytest
from pypdf import PdfWriter
from studyos_ai.config import Settings
from studyos_ai.egress import validate_url
from studyos_ai.errors import PipelineError
from studyos_ai.ingestion import Section, chunk_sections, parse_source
from worker.transcripts import fetch_transcript, youtube_video_id


@pytest.mark.parametrize(
    "address",
    [
        "127.0.0.1",
        "10.1.2.3",
        "169.254.169.254",
        "192.168.1.1",
        "100.64.0.1",
        "0.0.0.0",
        "::1",
        "fc00::1",
        "::ffff:127.0.0.1",
        "2002:7f00:1::",
    ],
)
def test_ssrf_blocks_nonpublic_or_transition_addresses(address):
    def resolver(*args, **kwargs):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 443))]

    with pytest.raises(PipelineError, match="SOURCE_URL_BLOCKED"):
        validate_url("https://example.org/page", resolver)


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "http://localhost/",
        "http://metadata.google.internal/",
        "https://user:pass@example.com/",
        "http://example.com:8080/",
        "http://example.com\\@127.0.0.1/",
        "http://example.com/\r\nHost: localhost",
    ],
)
def test_ssrf_rejects_unsafe_url_shapes(url):
    with pytest.raises(PipelineError, match="SOURCE_URL_BLOCKED"):
        validate_url(url)


def test_ssrf_checks_all_dns_answers_and_returns_pinned_literal():
    public = (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))
    private = (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443))
    with pytest.raises(PipelineError):
        validate_url("https://example.org", lambda *a, **k: [public, private])
    assert validate_url("https://example.org/path?q=ok", lambda *a, **k: [public]) == (
        "https",
        "example.org",
        443,
        "93.184.216.34",
        "/path?q=ok",
    )


def test_html_parser_removes_active_hidden_content_and_preserves_heading():
    sections = parse_source(
        b"<html><script>steal()</script><main><h1>Biology</h1><p>Plants use sunlight.</p><p hidden>secret</p></main></html>",
        "WEB",
    )
    assert sections[-1].heading == "Biology"
    assert "Plants" in sections[-1].text
    assert all("steal" not in s.text and "secret" not in s.text for s in sections)


def test_chunk_keys_are_reproducible_and_keep_page_time_provenance():
    version = uuid4()
    sections = [
        Section("page-1", "word " * 1500, page_no=1),
        Section(
            "segment-2", "Timestamped transcript content.", start_ms=1000, end_ms=2000
        ),
    ]
    chunks = chunk_sections(version, sections)
    assert chunks == chunk_sections(version, sections)
    assert len({c["id"] for c in chunks}) == len(chunks)
    assert chunks[-1]["start_ms"] == 1000
    assert all(c["page_no"] == 1 for c in chunks[:-1])


def test_parser_rejects_corrupt_pdf_and_empty_scan():
    with pytest.raises(PipelineError, match="SOURCE_CORRUPT_PDF"):
        parse_source(b"not PDF", "PDF")
    writer = PdfWriter()
    writer.add_blank_page(width=300, height=300)
    target = io.BytesIO()
    writer.write(target)
    with pytest.raises(PipelineError, match="SOURCE_NO_TEXT"):
        parse_source(target.getvalue(), "PDF")


def test_raw_utf8_and_corrupt_encoding():
    assert (
        parse_source("Kiến thức học tập\x00\r\n\r\nNguồn mới".encode(), "RAW_TEXT")[
            0
        ].text
        == "Kiến thức học tập"
    )
    with pytest.raises(PipelineError, match="SOURCE_PARSE_FAILED"):
        parse_source(b"\xff\xfe\xff", "RAW_TEXT")


async def test_transcript_unavailable_never_fabricates_segments():
    settings = Settings(
        _env_file=None,
        internal_jwt_secret="test-secret-only-at-least-thirty-two-characters",
        transcript_provider_url=None,
        model_provider="local-extractive",
    )
    with pytest.raises(PipelineError, match="TRANSCRIPT_UNAVAILABLE"):
        await fetch_transcript(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", ["en"], settings
        )
    assert youtube_video_id("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    with pytest.raises(PipelineError):
        youtube_video_id("https://youtube.com.attacker.com/watch?v=dQw4w9WgXcQ")
