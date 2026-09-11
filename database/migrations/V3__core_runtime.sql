ALTER TABLE refresh_tokens ADD COLUMN family_id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);
ALTER TABLE sources ADD COLUMN idempotency_key varchar(120);
CREATE UNIQUE INDEX uq_sources_create_idempotency ON sources(created_by,notebook_id,idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE TABLE conversation_generations(
    request_id uuid NOT NULL,
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    assistant_message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id),
    source_ids_json jsonb NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'STREAMING' CHECK(status IN ('STREAMING','COMPLETED','FAILED','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    PRIMARY KEY(conversation_id,request_id)
);
CREATE UNIQUE INDEX uq_conversation_active_generation ON conversation_generations(conversation_id) WHERE status='STREAMING';
CREATE INDEX idx_generation_active_user ON conversation_generations(user_id) WHERE status='STREAMING';

