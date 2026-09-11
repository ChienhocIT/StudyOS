-- Additive runtime requirements absent from the frozen Phase 0 baseline.
ALTER TABLE artifact_jobs ADD COLUMN result_ref uuid;
ALTER TABLE artifact_jobs ADD COLUMN result_json jsonb;
ALTER TABLE artifact_jobs ADD COLUMN request_fingerprint varchar(64);

ALTER TABLE flashcard_reviews ADD COLUMN idempotency_key varchar(120);
CREATE UNIQUE INDEX uq_review_idempotency ON flashcard_reviews(user_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

ALTER TABLE mastery_evidence ADD COLUMN evidence_key varchar(160);
CREATE UNIQUE INDEX uq_mastery_evidence_key ON mastery_evidence(user_id, concept_id, evidence_key)
  WHERE evidence_key IS NOT NULL;

CREATE TABLE worker_outbox_events (
  id uuid PRIMARY KEY,
  event_json jsonb NOT NULL,
  exchange_name text NOT NULL,
  routing_key text NOT NULL,
  published_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_worker_outbox_pending ON worker_outbox_events(created_at)
  WHERE published_at IS NULL;

-- Nullable language keys must not bypass vocabulary deduplication.
CREATE UNIQUE INDEX uq_vocabulary_normalized_scope ON vocabulary_items
  (user_id, notebook_id, normalized_term, coalesce(source_language,''), coalesce(target_language,''));

CREATE UNIQUE INDEX uq_quiz_job ON quizzes(artifact_job_id) WHERE artifact_job_id IS NOT NULL;
CREATE UNIQUE INDEX uq_flashcard_job ON flashcard_decks(artifact_job_id) WHERE artifact_job_id IS NOT NULL;
CREATE INDEX idx_flashcard_due ON flashcards(due_at, id);
