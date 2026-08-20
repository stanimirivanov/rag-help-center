# ADR 0004: Embedding worker projection and idempotency

- Status: Accepted
- Date: 2026-08-20

## Context

Kafka delivery is at least once, model calls can fail, and an article can
publish multiple revisions. Redelivery must not duplicate chunks or status
events, and an older revision must not remain searchable after replacement or
withdrawal.

## Decision

- The worker claims the integration event ID in `embedding_inbox` in the same
  PostgreSQL transaction as vector replacement and the index-status outbox
  record.
- Chunk IDs are deterministic across replays. Before inserting a revision, the
  worker marks every existing chunk for that tenant/article inactive, then
  upserts the new revision.
- Withdrawal marks all article chunks inactive rather than deleting their audit
  data.
- Ollama is the default local Spring AI `EmbeddingModel`; a deterministic
  SHA-256-derived adapter is selected for CI and tests.
- The worker uses its own Flyway history table because local development
  currently shares a PostgreSQL schema with ingestion while each service owns
  independent migration versions.

## Consequences

- A failed transaction rolls back the inbox claim, so Kafka redelivery can retry
  the complete operation.
- Successful redelivery becomes a no-op and cannot duplicate status output.
- Retrieval must always filter for active chunks.
- Variable-dimension `vector` columns support the fake and Ollama adapters
  during development; an indexed fixed dimension must be selected with the
  production embedding model.
