from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel


class Contract(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, extra="forbid"
    )


class InternalContext(Contract):
    user_id: UUID
    workspace_id: UUID
    notebook_id: UUID
    source_ids: list[UUID] = Field(max_length=100)
    conversation_id: UUID | None = None
    trace_id: str = Field(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    permissions: list[str] = Field(min_length=1, max_length=20)

    @field_validator("source_ids")
    @classmethod
    def unique_sources(cls, value):
        if len(value) != len(set(value)):
            raise ValueError("sourceIds must be unique")
        return value


class HistoryMessage(Contract):
    role: Literal["user", "assistant", "USER", "ASSISTANT"]
    content: str = Field(max_length=16000)


class ChatRequest(Contract):
    context: InternalContext
    request_id: UUID
    message_id: UUID
    content: str = Field(min_length=1, max_length=16000)
    mode: Literal["ASK", "SOCRATIC", "FEYNMAN", "EXAM"] = "ASK"
    history: list[HistoryMessage] = Field(default_factory=list, max_length=20)
    learner_state: dict = Field(default_factory=dict)


class RetrievalRequest(Contract):
    context: InternalContext
    query: str = Field(min_length=1, max_length=4000)


class ArtifactRequest(Contract):
    context: InternalContext
    artifact_type: Literal["QUIZ", "FLASHCARDS", "STUDY_GUIDE"]
    options: dict = Field(default_factory=dict)


class LanguageRequest(Contract):
    context: InternalContext
    sentence: str = Field(min_length=1, max_length=5000)
    source_language: str = Field(default="en", max_length=16)
    target_language: str = Field(default="vi", max_length=16)
    learner_level: Literal["A1", "A2", "B1", "B2", "C1", "C2"] = "B1"


class Vocabulary(Contract):
    term: str = Field(min_length=1, max_length=300)
    meaning: str = Field(min_length=1, max_length=2000)
    example: str = Field(default="", max_length=3000)


class Grammar(Contract):
    pattern: str = Field(min_length=1, max_length=300)
    explanation: str = Field(min_length=1, max_length=2000)


class LanguageAnalysis(Contract):
    translation: str = Field(min_length=1, max_length=10000)
    vocabulary: list[Vocabulary] = Field(max_length=40)
    grammar: list[Grammar] = Field(max_length=20)
