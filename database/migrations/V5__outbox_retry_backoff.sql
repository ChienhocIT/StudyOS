ALTER TABLE outbox_events ADD COLUMN next_publish_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX idx_outbox_retry_ready ON outbox_events(next_publish_at, occurred_at)
  WHERE published_at IS NULL;
