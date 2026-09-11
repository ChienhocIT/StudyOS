# Contracts

The original public contracts remain at repository root to preserve the design pack
and avoid competing sources of truth. `scripts/validate_contracts.py` validates them.
The generated internal AI contract is stored here as `openapi-ai-internal.yaml`.

Public REST: `../../openapi-core.yaml`. Events: `../../asyncapi-rabbitmq.yaml` and
`../../event-envelope.schema.json`. WebSocket: `../../websocket-protocol.schema.json`.

Generated web types live in `apps/web/src/shared/api` and must be regenerated after
public contract changes. Runtime additive details are recorded in
`docs/implementation/DECISIONS.md` and `runtime-additions.md`.
