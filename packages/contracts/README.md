# Contracts

All public contracts are in `Software Desgin/contracts/` at repository root.
`scripts/validate_contracts.py` validates them.
The generated internal AI contract is stored here as `openapi-ai-internal.yaml`.

Public REST: `../../Software Desgin/contracts/openapi-core.yaml`.
Events: `../../Software Desgin/contracts/asyncapi-rabbitmq.yaml` and
`../../Software Desgin/contracts/event-envelope.schema.json`.
WebSocket: `../../Software Desgin/contracts/websocket-protocol.schema.json`.

Generated web types live in `apps/web/src/shared/api` and must be regenerated after
public contract changes. Runtime additive details are recorded in
`docs/implementation/DECISIONS.md` and `runtime-additions.md`.
