-- StudyOS Phase 0 PostgreSQL schema baseline
-- PostgreSQL 16+ recommended; pgvector required. Adjust VECTOR_DIM if model changes.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TYPE account_status AS ENUM ('ACTIVE','SUSPENDED','DELETED');
CREATE TYPE workspace_role AS ENUM ('OWNER','ADMIN','MEMBER');
CREATE TYPE workspace_plan AS ENUM ('FREE','PRO','TEAM');
CREATE TYPE notebook_status AS ENUM ('ACTIVE','ARCHIVED','DELETED');
CREATE TYPE source_type AS ENUM ('PDF','WEB','YOUTUBE','RAW_TEXT','DOCX','PPTX','AUDIO','IMAGE');
CREATE TYPE source_status AS ENUM ('CREATED','UPLOADING','QUEUED','PARSING','NORMALIZED','CHUNKING','EMBEDDING','ENRICHING','READY','FAILED','DELETING','DELETED');
CREATE TYPE job_status AS ENUM ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED');
CREATE TYPE relation_type AS ENUM ('PREREQUISITE','PART_OF','RELATED_TO','CONTRASTS_WITH');
CREATE TYPE conversation_mode AS ENUM ('ASK','SOCRATIC','FEYNMAN','EXAM','INTERVIEW','ELI5','EXPERT');
CREATE TYPE message_role AS ENUM ('USER','ASSISTANT','SYSTEM','TOOL');
CREATE TYPE message_status AS ENUM ('PENDING','STREAMING','COMPLETED','FAILED','CANCELLED');
CREATE TYPE artifact_type AS ENUM ('QUIZ','FLASHCARDS','STUDY_GUIDE','SUMMARY','CHEAT_SHEET');
CREATE TYPE question_type AS ENUM ('MCQ','MULTI_SELECT','TRUE_FALSE','SHORT_ANSWER');
CREATE TYPE evidence_type AS ENUM ('QUIZ','FLASHCARD_RECALL','FEYNMAN','EXAM','INTERVIEW','MANUAL_ASSESSMENT','VIEW');
CREATE TYPE recommendation_action AS ENUM ('LEARN_NEW','REVIEW_FLASHCARDS','RETRY_QUIZ','EXPLAIN_CONCEPT','FEYNMAN_PRACTICE','CONTINUE_SOURCE');
CREATE TYPE recommendation_status AS ENUM ('PENDING','ACCEPTED','COMPLETED','DISMISSED','EXPIRED');

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email citext NOT NULL UNIQUE,
  password_hash text,
  display_name varchar(120) NOT NULL,
  locale varchar(16) NOT NULL DEFAULT 'vi-VN',
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Bangkok',
  status account_status NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_identities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider varchar(32) NOT NULL,
  provider_subject varchar(255) NOT NULL,
  email_at_provider citext,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(provider, provider_subject)
);

CREATE TABLE refresh_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash char(64) NOT NULL UNIQUE,
  device_label varchar(120),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens(user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE workspaces (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid NOT NULL REFERENCES users(id),
  name varchar(160) NOT NULL,
  plan workspace_plan NOT NULL DEFAULT 'FREE',
  status varchar(24) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role workspace_role NOT NULL,
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(workspace_id, user_id)
);
CREATE INDEX idx_workspace_members_user ON workspace_members(user_id, workspace_id);

CREATE TABLE notebooks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  created_by uuid NOT NULL REFERENCES users(id),
  title varchar(200) NOT NULL,
  description text,
  goal_text text,
  status notebook_status NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notebooks_workspace_status ON notebooks(workspace_id, status, updated_at DESC);

CREATE TABLE sources (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  created_by uuid NOT NULL REFERENCES users(id),
  type source_type NOT NULL,
  title varchar(300),
  canonical_uri text,
  current_version_id uuid,
  status source_status NOT NULL DEFAULT 'CREATED',
  language varchar(16),
  failure_code varchar(80),
  failure_message text,
  retryable boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sources_notebook_status ON sources(notebook_id, status, updated_at DESC);
CREATE INDEX idx_sources_workspace ON sources(workspace_id, notebook_id);

CREATE TABLE source_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_id uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  version_no int NOT NULL CHECK(version_no > 0),
  object_key text,
  normalized_object_key text,
  checksum_sha256 char(64),
  mime_type varchar(120),
  size_bytes bigint CHECK(size_bytes IS NULL OR size_bytes >= 0),
  parse_status job_status NOT NULL DEFAULT 'PENDING',
  parser_version varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(source_id, version_no)
);
ALTER TABLE sources ADD CONSTRAINT fk_sources_current_version FOREIGN KEY(current_version_id) REFERENCES source_versions(id) DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX uq_source_checksum ON source_versions(source_id, checksum_sha256) WHERE checksum_sha256 IS NOT NULL;

CREATE TABLE source_processing_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_version_id uuid NOT NULL REFERENCES source_versions(id) ON DELETE CASCADE,
  stage varchar(32) NOT NULL,
  status job_status NOT NULL DEFAULT 'PENDING',
  attempt int NOT NULL DEFAULT 0,
  max_attempts int NOT NULL DEFAULT 5,
  error_code varchar(80),
  error_detail text,
  started_at timestamptz,
  finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(source_version_id, stage)
);
CREATE INDEX idx_processing_jobs_status ON source_processing_jobs(status, stage, created_at);

CREATE TABLE document_sections (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_version_id uuid NOT NULL REFERENCES source_versions(id) ON DELETE CASCADE,
  section_key varchar(160) NOT NULL,
  parent_section_id uuid REFERENCES document_sections(id) ON DELETE SET NULL,
  heading text,
  ordinal int NOT NULL,
  page_start int,
  page_end int,
  time_start_ms bigint,
  time_end_ms bigint,
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE(source_version_id, section_key)
);
CREATE INDEX idx_sections_source_ordinal ON document_sections(source_version_id, ordinal);

CREATE TABLE document_chunks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  source_version_id uuid NOT NULL REFERENCES source_versions(id) ON DELETE CASCADE,
  section_id uuid REFERENCES document_sections(id) ON DELETE SET NULL,
  chunk_key varchar(160) NOT NULL,
  ordinal int NOT NULL,
  text text NOT NULL,
  page_no int,
  start_ms bigint,
  end_ms bigint,
  token_count int,
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  embedding_model varchar(120),
  embedding vector(1536),
  search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(text,''))) STORED,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(source_version_id, chunk_key)
);
CREATE INDEX idx_chunks_scope ON document_chunks(workspace_id, notebook_id, source_version_id);
CREATE INDEX idx_chunks_fts ON document_chunks USING gin(search_vector);
CREATE INDEX idx_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops) WHERE embedding IS NOT NULL;

CREATE TABLE transcript_segments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  source_version_id uuid NOT NULL REFERENCES source_versions(id) ON DELETE CASCADE,
  segment_no int NOT NULL,
  start_ms bigint NOT NULL,
  end_ms bigint NOT NULL,
  text text NOT NULL,
  language varchar(16),
  speaker varchar(120),
  UNIQUE(source_version_id, segment_no),
  CHECK(end_ms >= start_ms)
);
CREATE INDEX idx_transcript_source_time ON transcript_segments(source_version_id, start_ms);

CREATE TABLE concepts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  name varchar(200) NOT NULL,
  normalized_name varchar(200) NOT NULL,
  description text,
  difficulty numeric(4,3) CHECK(difficulty IS NULL OR difficulty BETWEEN 0 AND 1),
  extraction_confidence numeric(4,3) CHECK(extraction_confidence IS NULL OR extraction_confidence BETWEEN 0 AND 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(notebook_id, normalized_name)
);
CREATE INDEX idx_concepts_notebook ON concepts(notebook_id, normalized_name);

CREATE TABLE concept_relations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  from_concept_id uuid NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
  to_concept_id uuid NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
  relation_type relation_type NOT NULL,
  confidence numeric(4,3) NOT NULL CHECK(confidence BETWEEN 0 AND 1),
  evidence_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  UNIQUE(from_concept_id, to_concept_id, relation_type),
  CHECK(from_concept_id <> to_concept_id)
);
CREATE INDEX idx_concept_rel_from ON concept_relations(from_concept_id, relation_type);
CREATE INDEX idx_concept_rel_to ON concept_relations(to_concept_id, relation_type);

CREATE TABLE chunk_concepts (
  chunk_id uuid NOT NULL REFERENCES document_chunks(id) ON DELETE CASCADE,
  concept_id uuid NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
  confidence numeric(4,3) NOT NULL CHECK(confidence BETWEEN 0 AND 1),
  PRIMARY KEY(chunk_id, concept_id)
);
CREATE INDEX idx_chunk_concepts_concept ON chunk_concepts(concept_id, chunk_id);

CREATE TABLE user_concept_mastery (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  concept_id uuid NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
  mastery_score numeric(5,4) NOT NULL DEFAULT 0 CHECK(mastery_score BETWEEN 0 AND 1),
  confidence numeric(5,4) NOT NULL DEFAULT 0 CHECK(confidence BETWEEN 0 AND 1),
  evidence_count int NOT NULL DEFAULT 0,
  last_evidence_at timestamptz,
  next_recommended_at timestamptz,
  version bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(user_id, concept_id)
);
CREATE INDEX idx_mastery_user_score ON user_concept_mastery(user_id, mastery_score, confidence);

CREATE TABLE mastery_evidence (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  concept_id uuid NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
  evidence_type evidence_type NOT NULL,
  score numeric(5,4) NOT NULL CHECK(score BETWEEN 0 AND 1),
  weight numeric(5,4) NOT NULL CHECK(weight BETWEEN 0 AND 1),
  source_ref jsonb NOT NULL DEFAULT '{}'::jsonb,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_mastery_evidence_user_concept ON mastery_evidence(user_id, concept_id, occurred_at DESC);

CREATE TABLE conversations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mode conversation_mode NOT NULL DEFAULT 'ASK',
  title varchar(240),
  summary text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversations_user_notebook ON conversations(user_id, notebook_id, updated_at DESC);

CREATE TABLE messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  request_id uuid,
  role message_role NOT NULL,
  content text NOT NULL DEFAULT '',
  model varchar(120),
  status message_status NOT NULL DEFAULT 'COMPLETED',
  sequence_no bigint NOT NULL,
  grounding_status varchar(24),
  trace_id varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(conversation_id, sequence_no),
  UNIQUE(conversation_id, request_id)
);
CREATE INDEX idx_messages_conversation_seq ON messages(conversation_id, sequence_no);

CREATE TABLE citations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  chunk_id uuid NOT NULL REFERENCES document_chunks(id) ON DELETE RESTRICT,
  citation_key varchar(16) NOT NULL,
  quote_start int,
  quote_end int,
  rank int,
  confidence numeric(4,3) CHECK(confidence IS NULL OR confidence BETWEEN 0 AND 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(message_id, citation_key)
);
CREATE INDEX idx_citations_chunk ON citations(chunk_id);

CREATE TABLE message_feedback (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  rating smallint NOT NULL CHECK(rating IN (-1,1)),
  reason varchar(80),
  comment text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(message_id, user_id)
);

CREATE TABLE notes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title varchar(240) NOT NULL,
  content text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_notes_notebook_updated ON notes(notebook_id, updated_at DESC);

CREATE TABLE note_citations (
  note_id uuid NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
  citation_id uuid NOT NULL REFERENCES citations(id) ON DELETE RESTRICT,
  PRIMARY KEY(note_id, citation_id)
);

CREATE TABLE artifact_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  artifact_type artifact_type NOT NULL,
  status job_status NOT NULL DEFAULT 'PENDING',
  scope_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  options_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  idempotency_key varchar(120),
  error_code varchar(80),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(user_id, idempotency_key)
);
CREATE INDEX idx_artifact_jobs_user_status ON artifact_jobs(user_id, status, created_at DESC);

CREATE TABLE quizzes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  artifact_job_id uuid REFERENCES artifact_jobs(id) ON DELETE SET NULL,
  title varchar(240) NOT NULL,
  difficulty numeric(4,3) CHECK(difficulty IS NULL OR difficulty BETWEEN 0 AND 1),
  status varchar(24) NOT NULL DEFAULT 'READY',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE quiz_questions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quiz_id uuid NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
  ordinal int NOT NULL,
  question_type question_type NOT NULL,
  prompt text NOT NULL,
  answer_json jsonb NOT NULL,
  explanation text,
  source_refs_json jsonb NOT NULL DEFAULT '[]'::jsonb,
  UNIQUE(quiz_id, ordinal)
);

CREATE TABLE quiz_question_concepts (
  question_id uuid NOT NULL REFERENCES quiz_questions(id) ON DELETE CASCADE,
  concept_id uuid NOT NULL REFERENCES concepts(id) ON DELETE CASCADE,
  PRIMARY KEY(question_id, concept_id)
);

CREATE TABLE quiz_attempts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  quiz_id uuid NOT NULL REFERENCES quizzes(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  started_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  score numeric(5,4) CHECK(score IS NULL OR score BETWEEN 0 AND 1),
  status varchar(24) NOT NULL DEFAULT 'IN_PROGRESS'
);
CREATE INDEX idx_quiz_attempts_user ON quiz_attempts(user_id, started_at DESC);

CREATE TABLE quiz_answers (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  attempt_id uuid NOT NULL REFERENCES quiz_attempts(id) ON DELETE CASCADE,
  question_id uuid NOT NULL REFERENCES quiz_questions(id) ON DELETE CASCADE,
  answer_json jsonb NOT NULL,
  correctness boolean,
  score numeric(5,4) CHECK(score IS NULL OR score BETWEEN 0 AND 1),
  answered_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(attempt_id, question_id)
);

CREATE TABLE flashcard_decks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  artifact_job_id uuid REFERENCES artifact_jobs(id) ON DELETE SET NULL,
  title varchar(240) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE flashcards (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  deck_id uuid NOT NULL REFERENCES flashcard_decks(id) ON DELETE CASCADE,
  concept_id uuid REFERENCES concepts(id) ON DELETE SET NULL,
  source_chunk_id uuid REFERENCES document_chunks(id) ON DELETE SET NULL,
  front text NOT NULL,
  back text NOT NULL,
  scheduler_state_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  due_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_flashcards_deck_due ON flashcards(deck_id, due_at);

CREATE TABLE flashcard_reviews (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  card_id uuid NOT NULL REFERENCES flashcards(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  grade smallint NOT NULL CHECK(grade BETWEEN 1 AND 4),
  previous_state_json jsonb NOT NULL,
  new_state_json jsonb NOT NULL,
  reviewed_at timestamptz NOT NULL DEFAULT now(),
  next_review_at timestamptz NOT NULL
);
CREATE INDEX idx_card_reviews_user_date ON flashcard_reviews(user_id, reviewed_at DESC);

CREATE TABLE learning_goals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  title varchar(240) NOT NULL,
  description text,
  target_date date,
  weekly_minutes int CHECK(weekly_minutes IS NULL OR weekly_minutes BETWEEN 15 AND 10080),
  status varchar(24) NOT NULL DEFAULT 'ACTIVE',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE learning_recommendations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  concept_id uuid REFERENCES concepts(id) ON DELETE SET NULL,
  action_type recommendation_action NOT NULL,
  priority_score numeric(8,5) NOT NULL,
  reason_json jsonb NOT NULL,
  status recommendation_status NOT NULL DEFAULT 'PENDING',
  generated_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz
);
CREATE INDEX idx_recommendations_user_status ON learning_recommendations(user_id, status, priority_score DESC);

CREATE TABLE study_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  activity_type varchar(40) NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  ended_at timestamptz,
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  CHECK(ended_at IS NULL OR ended_at >= started_at)
);

CREATE TABLE vocabulary_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  notebook_id uuid NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  source_id uuid REFERENCES sources(id) ON DELETE SET NULL,
  transcript_segment_id uuid REFERENCES transcript_segments(id) ON DELETE SET NULL,
  term varchar(300) NOT NULL,
  normalized_term varchar(300) NOT NULL,
  meaning text,
  context text,
  source_language varchar(16),
  target_language varchar(16),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(user_id, notebook_id, normalized_term, source_language, target_language)
);
CREATE INDEX idx_vocab_user_notebook ON vocabulary_items(user_id, notebook_id, created_at DESC);

CREATE TABLE ai_runs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  workspace_id uuid REFERENCES workspaces(id) ON DELETE SET NULL,
  feature varchar(64) NOT NULL,
  model varchar(120) NOT NULL,
  prompt_version varchar(80),
  retrieval_config_version varchar(80),
  input_tokens int,
  output_tokens int,
  estimated_cost_usd numeric(12,6),
  ttft_ms int,
  latency_ms int,
  provider_status varchar(40),
  trace_id varchar(64) NOT NULL,
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_runs_workspace_date ON ai_runs(workspace_id, created_at DESC);
CREATE INDEX idx_ai_runs_trace ON ai_runs(trace_id);

CREATE TABLE audit_logs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  workspace_id uuid REFERENCES workspaces(id) ON DELETE SET NULL,
  action varchar(100) NOT NULL,
  entity_type varchar(80),
  entity_id uuid,
  outcome varchar(24) NOT NULL DEFAULT 'SUCCESS',
  trace_id varchar(64),
  metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_workspace_date ON audit_logs(workspace_id, created_at DESC);

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  aggregate_type varchar(80) NOT NULL,
  aggregate_id uuid NOT NULL,
  event_type varchar(120) NOT NULL,
  event_version int NOT NULL DEFAULT 1,
  payload_json jsonb NOT NULL,
  headers_json jsonb NOT NULL DEFAULT '{}'::jsonb,
  trace_id varchar(64),
  occurred_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  publish_attempts int NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(occurred_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
  consumer_name varchar(120) NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(consumer_name, event_id)
);

-- Recommended tenant-safe lookup indexes.
CREATE INDEX idx_chunks_notebook_source ON document_chunks(notebook_id, source_version_id, ordinal);
CREATE INDEX idx_messages_trace ON messages(trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_sources_canonical_uri ON sources(notebook_id, canonical_uri) WHERE canonical_uri IS NOT NULL;

-- Optional defense-in-depth for later: PostgreSQL Row Level Security.
-- MVP source of truth remains application authorization + mandatory workspace/notebook filters.
