ALTER TABLE flashcard_decks ADD COLUMN status varchar(24) NOT NULL DEFAULT 'READY';
ALTER TABLE artifact_jobs ADD COLUMN stale_at timestamptz;
CREATE TABLE ai_usage_reservations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
  feature varchar(64) NOT NULL,
  request_key varchar(160) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'RESERVED' CHECK(status IN ('RESERVED','COMPLETED','FAILED','CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  UNIQUE(user_id,feature,request_key)
);
CREATE INDEX idx_ai_reservations_daily ON ai_usage_reservations(user_id,created_at);
ALTER TABLE ai_runs ADD COLUMN reservation_id uuid UNIQUE REFERENCES ai_usage_reservations(id);
