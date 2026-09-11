import json
import re
from dataclasses import dataclass

from pydantic import ValidationError

from .contracts import ChatRequest, LanguageAnalysis, LanguageRequest
from .errors import PipelineError
from .prompts import (
    ARTIFACT_PROMPT_VERSION,
    BOUNDARY,
    LANGUAGE_PROMPT_VERSION,
    PROMPT_VERSION,
    chat_messages,
    evidence_data,
)
from .providers import LocalExtractiveProvider, ModelProvider
from .retrieval import Evidence, token_estimate

INJECTION = re.compile(
    r"(?:ignore\s+(?:all\s+)?(?:previous|prior|system)|system\s*prompt|developer\s*message|reveal\s+(?:secrets?|keys?|tokens?)|<\s*/?\s*system\b)",
    re.IGNORECASE,
)
CITATION = re.compile(r"\[(C\d+)\]")


@dataclass(frozen=True)
class GroundedAnswer:
    content: str
    grounding_status: str
    citations: list[dict]
    usage: dict

    def payload(self):
        return {
            "content": self.content,
            "groundingStatus": self.grounding_status,
            "citations": self.citations,
            "usage": self.usage,
            "promptVersion": PROMPT_VERSION,
        }


def validate_citations(
    content: str, evidence: list[Evidence]
) -> tuple[str, str, list[dict]]:
    allowed = {e.key: e for e in evidence}
    keys = list(dict.fromkeys(CITATION.findall(content)))
    invalid = set(keys) - allowed.keys()
    cleaned = CITATION.sub(
        lambda m: "" if m.group(1) in invalid else m.group(0), content
    )
    supported = [allowed[key].citation() for key in keys if key in allowed]
    # Key validity proves provenance, not semantic entailment: bounded certainty is explicit.
    status = "PARTIAL" if invalid else ("SUPPORTED" if supported else "INSUFFICIENT")
    return cleaned, status, supported


async def generate_answer(
    request: ChatRequest, evidence: list[Evidence], provider: ModelProvider
) -> GroundedAnswer:
    if not evidence:
        return GroundedAnswer(
            "ChÆ°a cÃ³ Ä‘á»§ báº±ng chá»©ng trong cÃ¡c nguá»“n Ä‘Ã£ chá»n Ä‘á»ƒ tráº£ lá»i cÃ¢u há»i nÃ y.",
            "INSUFFICIENT",
            [],
            {"model": provider.model, "inputTokens": 0, "outputTokens": 0},
        )
    if isinstance(provider, LocalExtractiveProvider):
        # Development output quotes source sentences only; suspicious instructions are excluded.
        query_words = set(re.findall(r"\w+", request.content.casefold()))
        candidates = []
        for item in evidence:
            for sentence in re.split(r"(?<=[.!?])\s+|\n+", item.text):
                sentence = sentence.strip()
                if not sentence or INJECTION.search(sentence):
                    continue
                overlap = len(
                    query_words & set(re.findall(r"\w+", sentence.casefold()))
                )
                if overlap:
                    candidates.append(
                        (overlap, len(sentence), item.key, sentence[:1200])
                    )
        candidates.sort(key=lambda c: (-c[0], c[1]))
        selected = candidates[:3]
        if not selected:
            return GroundedAnswer(
                "KhÃ´ng tÃ¬m tháº¥y Ä‘oáº¡n nguá»“n Ä‘á»§ liÃªn quan. HÃ£y chá»n thÃªm nguá»“n hoáº·c lÃ m rÃµ cÃ¢u há»i.",
                "INSUFFICIENT",
                [],
                {"model": provider.model, "inputTokens": 0, "outputTokens": 0},
            )
        prefix = "Cháº¿ Ä‘á»™ trÃ­ch dáº«n cá»¥c bá»™ â€” cÃ¡c Ä‘oáº¡n liÃªn quan trong nguá»“n:\n\n"
        if request.mode in {"SOCRATIC", "EXAM"}:
            prefix = "Dá»±a vÃ o Ä‘oáº¡n trÃ­ch sau, báº¡n giáº£i thÃ­ch Ã½ chÃ­nh báº±ng lá»i cá»§a mÃ¬nh nhÆ° tháº¿ nÃ o?\n\n"
        elif request.mode == "FEYNMAN":
            prefix = "Cháº¿ Ä‘á»™ cá»¥c bá»™ chá»‰ cung cáº¥p Ä‘oáº¡n Ä‘á»‘i chiáº¿u; Ä‘Ã¡nh giÃ¡ giáº£i thÃ­ch cáº§n cáº¥u hÃ¬nh mÃ´ hÃ¬nh AI.\n\n"
        content = prefix + "\n\n".join(
            f"> {re.sub(CITATION, '', s)} [{key}]" for _, _, key, s in selected
        )
        usage = {
            "model": provider.model,
            "inputTokens": token_estimate(request.content),
            "outputTokens": token_estimate(content),
        }
    else:
        content, usage = await provider.generate(
            chat_messages(
                request.content,
                evidence,
                request.mode,
                [m.model_dump() for m in request.history],
                request.learner_state,
            )
        )
    content, status, citations = validate_citations(content, evidence)
    if not isinstance(provider, LocalExtractiveProvider) and status == "SUPPORTED":
        # Until entailment evaluation is calibrated, never overstate a model citation as proof.
        status = "PARTIAL"
    return GroundedAnswer(content, status, citations, usage)


def _count(options: dict) -> int:
    value = options.get("questionCount", options.get("count", 5))
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 50:
        raise PipelineError(
            "ARTIFACT_OPTIONS_INVALID", "Artifact count must be between 1 and 50."
        )
    return value


def validate_artifact(artifact: dict, kind: str, evidence: list[Evidence]) -> dict:
    allowed = {e.chunk.id: e.chunk.source_id for e in evidence}
    title = artifact.get("title")
    if not isinstance(title, str) or not 1 <= len(title) <= 240:
        raise PipelineError(
            "ARTIFACT_OUTPUT_INVALID", "Generated artifact has an invalid title."
        )
    if kind == "QUIZ":
        questions = artifact.get("questions", [])
        if not isinstance(questions, list) or not 1 <= len(questions) <= 50:
            raise PipelineError(
                "ARTIFACT_OUTPUT_INVALID", "Quiz must have 1 to 50 valid questions."
            )
        for question in questions:
            if not isinstance(question, dict) or question.get("type") not in {
                "SHORT_ANSWER",
                "MCQ",
                "TRUE_FALSE",
            }:
                raise PipelineError("ARTIFACT_OUTPUT_INVALID", "Invalid question type.")
            if (
                not isinstance(question.get("prompt"), str)
                or not 1 <= len(question["prompt"]) <= 4000
            ):
                raise PipelineError(
                    "ARTIFACT_OUTPUT_INVALID", "Invalid question prompt."
                )
            answer = question.get("answer", {})
            if not isinstance(answer, dict) or "correctAnswer" not in answer:
                raise PipelineError(
                    "ARTIFACT_OUTPUT_INVALID", "Question is missing its answer."
                )
            if question["type"] == "MCQ":
                choices = answer.get("options", [])
                if (
                    not isinstance(choices, list)
                    or not 2 <= len(choices) <= 6
                    or answer["correctAnswer"] not in choices
                    or len(set(choices)) != len(choices)
                ):
                    raise PipelineError(
                        "ARTIFACT_OUTPUT_INVALID",
                        "Multiple-choice question has invalid answer options.",
                    )
            refs = question.get("sourceRefs", [])
            if not refs or any(
                not isinstance(ref, dict)
                or ref.get("chunkId") not in allowed
                or ref.get("sourceId") != allowed.get(ref.get("chunkId"))
                for ref in refs
            ):
                raise PipelineError(
                    "ARTIFACT_PROVENANCE_INVALID",
                    "Question references unsupported source evidence.",
                )
            question[
                "conceptIds"
            ] = []  # Only Core may attach accepted, tenant-checked concepts.
    elif kind == "FLASHCARDS":
        cards = artifact.get("cards", [])
        if not isinstance(cards, list) or not 1 <= len(cards) <= 50:
            raise PipelineError(
                "ARTIFACT_OUTPUT_INVALID", "Deck must contain 1 to 50 cards."
            )
        for card in cards:
            if (
                not isinstance(card, dict)
                or card.get("sourceChunkId") not in allowed
                or any(
                    not isinstance(card.get(k), str) or not 1 <= len(card[k]) <= 4000
                    for k in ("front", "back")
                )
            ):
                raise PipelineError(
                    "ARTIFACT_PROVENANCE_INVALID",
                    "Card lacks valid content or source evidence.",
                )
    elif (
        not isinstance(artifact.get("content"), str)
        or not 1 <= len(artifact["content"]) <= 40000
    ):
        raise PipelineError("ARTIFACT_OUTPUT_INVALID", "Invalid study guide content.")
    if len(json.dumps(artifact).encode()) > 180000:
        raise PipelineError(
            "ARTIFACT_OUTPUT_INVALID", "Generated artifact exceeds event size limit."
        )
    return artifact


async def generate_artifact(
    kind: str, options: dict, evidence: list[Evidence], provider: ModelProvider
) -> dict:
    if not evidence:
        raise PipelineError(
            "ARTIFACT_INSUFFICIENT_EVIDENCE",
            "Select at least one ready source with usable evidence.",
        )
    count = _count(options)
    if isinstance(provider, LocalExtractiveProvider):
        facts = []
        for item in evidence:
            for sentence in re.split(r"(?<=[.!?])\s+|\n+", item.text):
                words = list(re.finditer(r"\b[^\W\d_]{4,}\b", sentence, re.UNICODE))
                if (
                    len(words) >= 3
                    and not INJECTION.search(sentence)
                    and len(sentence) <= 1600
                ):
                    keyword = max(words, key=lambda m: len(m.group()))
                    facts.append((item, sentence, keyword))
                if len(facts) >= count:
                    break
            if len(facts) >= count:
                break
        if not facts:
            raise PipelineError(
                "ARTIFACT_INSUFFICIENT_EVIDENCE",
                "The selected evidence has no usable study statements.",
            )
        artifact = {
            "title": str(options.get("title", "Ã”n táº­p tá»« nguá»“n Ä‘Ã£ chá»n"))[:240]
        }
        if kind == "QUIZ":
            artifact["questions"] = [
                {
                    "type": "SHORT_ANSWER",
                    "prompt": "Äiá»n tá»« cÃ²n thiáº¿u: "
                    + sentence[: word.start()]
                    + "_____"
                    + sentence[word.end() :],
                    "answer": {"correctAnswer": word.group()},
                    "explanation": sentence,
                    "sourceRefs": [
                        {"chunkId": item.chunk.id, "sourceId": item.chunk.source_id}
                    ],
                    "conceptIds": [],
                }
                for item, sentence, word in facts
            ]
        elif kind == "FLASHCARDS":
            artifact["cards"] = [
                {
                    "front": "Äiá»n tá»«: "
                    + sentence[: word.start()]
                    + "_____"
                    + sentence[word.end() :],
                    "back": word.group() + "\n\n" + sentence,
                    "sourceChunkId": item.chunk.id,
                }
                for item, sentence, word in facts
            ]
        else:
            artifact["content"] = "# Äoáº¡n Ã´n táº­p tá»« nguá»“n\n\n" + "\n\n".join(
                f"- {sentence} [{item.key}]" for item, sentence, _ in facts
            )
    else:
        schema = {
            "QUIZ": '{"title":"...","questions":[{"type":"SHORT_ANSWER","prompt":"...","answer":{"correctAnswer":"..."},"explanation":"...","sourceRefs":[{"chunkId":"...","sourceId":"..."}],"conceptIds":[]}]}',
            "FLASHCARDS": '{"title":"...","cards":[{"front":"...","back":"...","sourceChunkId":"..."}]}',
            "STUDY_GUIDE": '{"title":"...","content":"Markdown with [C1] citations"}',
        }[kind]
        provenance = [
            {"key": e.key, "chunkId": e.chunk.id, "sourceId": e.chunk.source_id}
            for e in evidence
        ]
        raw, _ = await provider.generate(
            [
                {
                    "role": "system",
                    "content": BOUNDARY
                    + f"\nGenerate {count} grounded study items. Return JSON only with this schema: "
                    + schema,
                },
                {"role": "user", "content": evidence_data(evidence)},
                {
                    "role": "user",
                    "content": json.dumps(
                        {"provenance": provenance, "options": options}
                    ),
                },
            ],
            json_output=True,
        )
        try:
            artifact = json.loads(raw)
        except (ValueError, TypeError) as exc:
            raise PipelineError(
                "ARTIFACT_OUTPUT_INVALID", "Model did not produce valid JSON."
            ) from exc
    if not isinstance(artifact, dict):
        raise PipelineError(
            "ARTIFACT_OUTPUT_INVALID", "Model did not produce an artifact object."
        )
    return {
        "artifact": validate_artifact(artifact, kind, evidence),
        "provenance": {
            "model": provider.model,
            "promptVersion": ARTIFACT_PROMPT_VERSION,
        },
    }


async def analyze_language(request: LanguageRequest, provider: ModelProvider) -> dict:
    raw, usage = await provider.generate(
        [
            {
                "role": "system",
                "content": BOUNDARY
                + '\nAnalyze the supplied sentence. Return JSON {"translation":"...","vocabulary":[{"term":"...","meaning":"...","example":"..."}],"grammar":[{"pattern":"...","explanation":"..."}]}.',
            },
            {"role": "user", "content": request.model_dump_json(exclude={"context"})},
        ],
        json_output=True,
    )
    try:
        analysis = LanguageAnalysis.model_validate_json(raw)
    except ValidationError as exc:
        raise PipelineError(
            "LANGUAGE_OUTPUT_INVALID", "Model returned an invalid language analysis."
        ) from exc
    return {
        **analysis.model_dump(by_alias=True),
        "usage": usage,
        "promptVersion": LANGUAGE_PROMPT_VERSION,
    }
