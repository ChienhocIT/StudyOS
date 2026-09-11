import hashlib
import io
import re
import unicodedata
from dataclasses import asdict, dataclass
from uuid import UUID, uuid5

from bs4 import BeautifulSoup
from pypdf import PdfReader

from .errors import PipelineError
from .retrieval import token_estimate

PARSER_VERSION = "normalized-v1"
CHUNKER_VERSION = "section-utf8-1800-overlap180-v1"


@dataclass(frozen=True)
class Section:
    key: str
    text: str
    heading: str = ""
    page_no: int | None = None
    start_ms: int | None = None
    end_ms: int | None = None


def normalize_text(text: str) -> str:
    text = unicodedata.normalize("NFC", text).replace("\x00", "")
    text = re.sub(r"[^\S\n]+", " ", text.replace("\r\n", "\n").replace("\r", "\n"))
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def parse_source(data: bytes, source_type: str, max_pages: int = 500) -> list[Section]:
    try:
        if source_type == "PDF":
            if not data.startswith(b"%PDF-"):
                raise PipelineError(
                    "SOURCE_CORRUPT_PDF", "The uploaded file is not a valid PDF."
                )
            pdf = PdfReader(io.BytesIO(data), strict=False)
            if pdf.is_encrypted:
                raise PipelineError(
                    "SOURCE_ENCRYPTED_PDF", "Password-protected PDFs are not supported."
                )
            if len(pdf.pages) > max_pages:
                raise PipelineError(
                    "SOURCE_TOO_MANY_PAGES", f"PDF exceeds the {max_pages}-page limit."
                )
            result = [
                Section(
                    f"page-{i + 1}",
                    normalize_text(page.extract_text() or ""),
                    page_no=i + 1,
                )
                for i, page in enumerate(pdf.pages)
            ]
        elif source_type in {"RAW_TEXT", "TEXT", "RAW"}:
            text = normalize_text(data.decode("utf-8-sig", errors="strict"))
            result = [
                Section(f"section-{i}", paragraph)
                for i, paragraph in enumerate(text.split("\n\n"))
                if paragraph.strip()
            ]
        elif source_type == "WEB":
            soup = BeautifulSoup(data, "html.parser")
            for element in soup(
                [
                    "script",
                    "style",
                    "noscript",
                    "iframe",
                    "form",
                    "nav",
                    "footer",
                    "svg",
                ]
            ):
                element.decompose()
            for element in soup.select("[hidden], [aria-hidden='true']"):
                element.decompose()
            container = soup.find("main") or soup.find("article") or soup.body or soup
            heading = ""
            result = []
            elements = container.find_all(
                ["h1", "h2", "h3", "h4", "p", "li", "pre", "blockquote"]
            )
            for element in elements:
                if element.find_parent(["p", "li", "pre", "blockquote"]):
                    continue
                text = normalize_text(element.get_text(" ", strip=True))
                if element.name.startswith("h"):
                    heading = text
                if text:
                    result.append(Section(f"html-{len(result)}", text, heading=heading))
            if not result:
                result = [
                    Section(
                        "html-0", normalize_text(container.get_text(" ", strip=True))
                    )
                ]
        else:
            raise PipelineError(
                "SOURCE_UNSUPPORTED_TYPE",
                "This source type is not supported by the parser.",
            )
    except PipelineError:
        raise
    except Exception as exc:
        raise PipelineError(
            "SOURCE_PARSE_FAILED",
            "The source could not be parsed; verify its format and encoding.",
        ) from exc
    result = [section for section in result if section.text.strip()]
    if not result:
        raise PipelineError(
            "SOURCE_NO_TEXT",
            "The source contains no extractable text. Scanned PDFs require OCR before upload.",
        )
    if sum(len(s.text.encode()) for s in result) > 20 * 1024 * 1024:
        raise PipelineError(
            "SOURCE_TEXT_TOO_LARGE",
            "Extracted text exceeds the supported processing limit.",
        )
    return result


def chunk_sections(
    version_id: UUID, sections: list[Section], size: int = 1800, overlap: int = 180
) -> list[dict]:
    if not 0 <= overlap < size:
        raise ValueError("Chunk overlap must be smaller than size")
    result = []
    for section in sections:
        start = 0
        part = 0
        while start < len(section.text):
            end = min(start + size, len(section.text))
            if end < len(section.text):
                boundary = section.text.rfind(" ", start + size // 2, end)
                if boundary > start:
                    end = boundary
            text = section.text[start:end].strip()
            key = f"{CHUNKER_VERSION}:{section.key}:{part}:{hashlib.sha256(text.encode()).hexdigest()[:16]}"
            result.append(
                {
                    "id": uuid5(version_id, key),
                    "chunk_key": key,
                    "section_id": uuid5(version_id, section.key),
                    "ordinal": len(result),
                    "text": text,
                    "page_no": section.page_no,
                    "start_ms": section.start_ms,
                    "end_ms": section.end_ms,
                    "token_count": token_estimate(text),
                    "metadata": {
                        "chunkerVersion": CHUNKER_VERSION,
                        "heading": section.heading,
                    },
                }
            )
            if end == len(section.text):
                break
            start = max(start + 1, end - overlap)
            part += 1
    return result


def normalized_payload(sections: list[Section]) -> dict:
    return {
        "parserVersion": PARSER_VERSION,
        "sections": [asdict(section) for section in sections],
    }


def concept_candidates(chunks: list[dict]) -> list[dict]:
    """Conservative proposals from explicit headings/definitions, never inferred mastery."""
    found = {}
    for chunk in chunks:
        heading = chunk.get("metadata", {}).get("heading", "").strip()
        names = [heading] if 3 <= len(heading) <= 120 else []
        for line in chunk["text"].splitlines():
            explicit_heading = re.match(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", line)
            if explicit_heading and 3 <= len(explicit_heading.group(1)) <= 120:
                names.append(explicit_heading.group(1))
            match = re.match(
                r"^([\w \t-]{3,80}?)\s+(?:is|are|means|refers to|là|được định nghĩa là)\s+",
                line.strip(), re.IGNORECASE,
            )
            if match:
                names.append(match.group(1).strip())
        for name in names:
            normalized = unicodedata.normalize("NFC", name).casefold()
            if normalized not in found:
                found[normalized] = {
                    "name": name,
                    "description": chunk["text"][:500],
                    "chunkIds": [],
                    "confidence": 0.65,
                }
            chunk_id = str(chunk["id"])
            if chunk_id not in found[normalized]["chunkIds"]:
                found[normalized]["chunkIds"].append(chunk_id)
    return list(found.values())[:30]
