-- A retry is a new processing version of the same immutable original bytes.
-- Checksum deduplication across attempts would prevent this fencing mechanism.
DROP INDEX uq_source_checksum;
CREATE INDEX idx_source_checksum ON source_versions(source_id, checksum_sha256)
    WHERE checksum_sha256 IS NOT NULL;

CREATE TABLE source_retry_requests (
    user_id uuid NOT NULL REFERENCES users(id),
    source_id uuid NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
    idempotency_key varchar(120) NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    source_version_id uuid NOT NULL REFERENCES source_versions(id) ON DELETE CASCADE,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, source_id, idempotency_key)
);
