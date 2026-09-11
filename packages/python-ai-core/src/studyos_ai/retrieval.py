import math
from dataclasses import asdict, dataclass

from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from .contracts import InternalContext
from .providers import ModelProvider

RETRIEVAL_VERSION = "hybrid-rrf60-v1"


@dataclass(frozen=True)
class Chunk:
    id: str
    source_id: str
    source_version_id: str
    text: str
    title: str = ""
    page_no: int | None = None
    start_ms: int | None = None
    end_ms: int | None = None
    score: float = 0.0


def reciprocal_rank_fusion(*rankings: list[Chunk], limit: int = 12) -> list[Chunk]:
    scores: dict[str, float] = {}
    values: dict[str, Chunk] = {}
    for ranking in rankings:
        seen = set()
        for rank, chunk in enumerate(ranking, 1):
            if chunk.id in seen:
                continue
            seen.add(chunk.id)
            values[chunk.id] = chunk
            scores[chunk.id] = scores.get(chunk.id, 0) + 1 / (60 + rank)
    return [
        Chunk(**{**asdict(values[key]), "score": scores[key]})
        for key in sorted(scores, key=lambda key: (-scores[key], key))[:limit]
    ]


def token_estimate(text: str) -> int:
    # Conservative UTF-8 budget also bounds CJK/Vietnamese text, unlike char/4.
    return max(1, math.ceil(len(text.encode("utf-8")) / 3))


@dataclass(frozen=True)
class Evidence:
    key: str
    chunk: Chunk
    text: str

    def citation(self) -> dict:
        return {
            "key": self.key,
            "chunkId": self.chunk.id,
            "sourceId": self.chunk.source_id,
            "sourceVersionId": self.chunk.source_version_id,
            "pageNo": self.chunk.page_no,
            "startMs": self.chunk.start_ms,
            "endMs": self.chunk.end_ms,
            "quote": self.text[:400],
        }


def build_context(chunks: list[Chunk], budget: int) -> list[Evidence]:
    evidence = []
    used = 0
    for chunk in chunks:
        # Budget includes the reference/title and JSON delimiter overhead.
        overhead = token_estimate(chunk.title) + 40
        remaining = budget - used - overhead
        if remaining < 32:
            break
        text = chunk.text
        if token_estimate(text) > remaining:
            raw = text.encode("utf-8")[: remaining * 3]
            text = raw.decode("utf-8", errors="ignore")
        if text.strip():
            evidence.append(Evidence(f"C{len(evidence) + 1}", chunk, text))
            used += overhead + token_estimate(text)
    return evidence


class ChunkRepository:
    # Scope lives in both denormalized chunks AND authoritative source/notebook joins.
    SCOPED_FROM = """
        FROM document_chunks c
        JOIN source_versions v ON v.id = c.source_version_id
        JOIN sources s ON s.id = v.source_id AND s.current_version_id = v.id
        JOIN notebooks n ON n.id = s.notebook_id
        WHERE c.workspace_id = %(workspace)s AND c.notebook_id = %(notebook)s
          AND s.workspace_id = %(workspace)s AND s.notebook_id = %(notebook)s
          AND n.workspace_id = %(workspace)s AND n.status = 'ACTIVE'
          AND s.status = 'READY' AND s.id = ANY(%(sources)s::uuid[])
    """
    COLUMNS = "c.id, s.id AS source_id, c.source_version_id, c.text, coalesce(s.title,'') AS title, c.page_no, c.start_ms, c.end_ms"

    def __init__(self, pool: AsyncConnectionPool):
        self.pool = pool

    async def search(
        self,
        context: InternalContext,
        query: str,
        vector: list[float],
        model: str,
        limit: int,
    ) -> list[Chunk]:
        if not context.source_ids:
            return []
        params = {
            "workspace": context.workspace_id,
            "notebook": context.notebook_id,
            "sources": context.source_ids,
            "query": query,
            "vector": str(vector),
            "model": model,
            "limit": limit * 3,
        }
        async with self.pool.connection() as conn:
            async with conn.cursor(row_factory=dict_row) as cur:
                await cur.execute(
                    "SELECT "
                    + self.COLUMNS
                    + self.SCOPED_FROM
                    + """
                    AND c.search_vector @@ plainto_tsquery('simple', %(query)s)
                    ORDER BY ts_rank_cd(c.search_vector, plainto_tsquery('simple', %(query)s)) DESC, c.id LIMIT %(limit)s
                """,
                    params,
                )
                lexical = self._chunks(await cur.fetchall())
                await cur.execute(
                    "SELECT "
                    + self.COLUMNS
                    + self.SCOPED_FROM
                    + """
                    AND c.embedding_model = %(model)s AND c.embedding IS NOT NULL
                    AND (c.embedding <=> %(vector)s::vector) < 0.85
                    ORDER BY c.embedding <=> %(vector)s::vector, c.id LIMIT %(limit)s
                """,
                    params,
                )
                semantic = self._chunks(await cur.fetchall())
        return reciprocal_rank_fusion(lexical, semantic, limit=limit)

    async def sample(self, context: InternalContext, limit: int = 30) -> list[Chunk]:
        if not context.source_ids:
            return []
        async with self.pool.connection() as conn:
            async with conn.cursor(row_factory=dict_row) as cur:
                await cur.execute(
                    "SELECT "
                    + self.COLUMNS
                    + self.SCOPED_FROM
                    + " ORDER BY s.id,c.ordinal LIMIT %(limit)s",
                    {
                        "workspace": context.workspace_id,
                        "notebook": context.notebook_id,
                        "sources": context.source_ids,
                        "limit": limit,
                    },
                )
                return self._chunks(await cur.fetchall())

    @staticmethod
    def _chunks(rows):
        return [
            Chunk(
                **{
                    **row,
                    "id": str(row["id"]),
                    "source_id": str(row["source_id"]),
                    "source_version_id": str(row["source_version_id"]),
                }
            )
            for row in rows
        ]


async def retrieve(
    repository: ChunkRepository,
    provider: ModelProvider,
    context: InternalContext,
    query: str,
    limit: int,
) -> list[Chunk]:
    if not context.source_ids:
        return []
    vector = (await provider.embed([query]))[0]
    return await repository.search(
        context, query, vector, provider.embedding_model, limit
    )
