# Implementation decisions

## Review and specification baseline

All work is reviewed against `2345ef0`, the existing Phase 0 commit. Original design
files remain unmodified. Runtime contracts and new migrations make additions explicit.

## Local AI provider

Local development may select an explicitly named extractive provider and deterministic
1536-dimensional hash embeddings to exercise the entire pipeline without credentials.
These are not a production LLM or semantic embedding quality benchmark. Production
must configure a model provider; changing embedding models requires reindexing.

## Learning policy v0

Objective quizzes are scored from the persisted answer key. Multiple-choice and
multi-select require exact matches; short answers are normalized by Unicode NFKC,
whitespace and case. Unsupported subjective grading is not silently delegated to AI.

Mastery is an evidence-weighted mean with a neutral prior of score 0.5 and weight 1.
Confidence is `totalWeight / (totalWeight + 3)`. Quiz evidence weight is 0.8; recall
evidence is at most 0.35 and reduced when the previous review was less than a day
ago. Passive views create no mastery evidence. Rules are versioned `mastery-v0`.

Review uses a deterministic conservative SM-2-inspired policy (`review-v0`), with
four grades (again/hard/good/easy), bounded ease, UTC instants and due-time checks.
It is not presented as FSRS or as a scientifically calibrated scheduler.

## Scope and integrity additions

Review requests require an Idempotency-Key scoped to the learner. A retry with
different card/grade is a conflict. Mastery evidence has a stable evidence key.
Worker derived writes and outbound facts commit in one database transaction using a
worker-owned outbox. Core remains the only owner of business state.
