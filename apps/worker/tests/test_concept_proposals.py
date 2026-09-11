from uuid import uuid4

from studyos_ai.ingestion import concept_candidates


def test_raw_markdown_preserves_heading_and_definition_evidence():
    chunk_id = str(uuid4())
    proposals = concept_candidates([{
        "id": chunk_id,
        "text": "# Database transactions\nAtomicity is all or nothing.\n"
                "Isolation is protection from incomplete changes.\nAtomicity is repeated.",
        "metadata": {},
    }])
    assert {p["name"] for p in proposals} == {"Database transactions", "Atomicity", "Isolation"}
    assert all(p["chunkIds"] == [chunk_id] for p in proposals)


def test_prose_without_explicit_definition_does_not_invent_concepts():
    assert concept_candidates([{"id": str(uuid4()), "text": "We discussed databases today."}]) == []
