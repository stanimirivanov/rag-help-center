# RAG Help Center — Implementation Plan

This plan is organized as vertical, demonstrable increments. Each phase ends with executable acceptance criteria. Create one issue and one pull request per phase; split a phase only when its acceptance criteria remain independently demonstrable.

## Definition of done for every phase

- `./mvnw verify` passes locally and in CI.
- New behavior has focused unit tests and integration tests where an adapter is involved.
- Public and internal contracts, migrations, configuration, and operational signals are documented.
- No credentials, generated build output, or environment-specific IDE state is committed.
- Architecture decisions that change a boundary are recorded in `docs/decisions/`.

## Phase 0 — Buildable foundation

**Goal:** make the repository a reliable starting point rather than a set of placeholders.

Scope:

- Maven reactor with `domain-kernel` plus five executable modules
- Kotlin 2.4.10, Java 25, Spring Boot 4.1.0, Spring Cloud 2025.1.2, Spring AI 2.0.0
- Maven wrapper, formatting/static analysis, CI build cache, and dependency convergence checks
- Local Compose dependencies: PostgreSQL/pgvector, Kafka in KRaft mode, Redis, MongoDB, and optional Ollama
- one application entry point per service and minimal context-load tests
- architecture and implementation documentation aligned with the actual repository

Acceptance criteria:

- [ ] `mvnw.cmd verify` and `./mvnw verify` are the documented build commands
- [ ] `docker compose config` succeeds
- [ ] `docker compose up -d postgres kafka redis mongodb` reaches healthy state
- [ ] CI runs `./mvnw -B -ntp verify` on pushes and pull requests
- [ ] no Gradle build files or gRPC implementation dependencies remain

## Phase 1 — Event-sourced article command slice

**Goal:** create and revise a help-center draft through a complete hexagonal slice.

Scope:

- `KnowledgeArticle` aggregate and immutable domain events in `ingestion-service`
- `POST /api/v1/articles` and `PUT /api/v1/articles/{id}/content`
- Postgres `article_events` table with unique `(tenant_id, aggregate_id, stream_version)`
- optimistic concurrency through an expected-version precondition
- event serialization with explicit event and schema versions
- Testcontainers integration tests and generated OpenAPI documentation

Acceptance criteria:

- [x] create returns `201`, article ID, and stream version
- [x] revise with the current version succeeds; stale revision returns `409`
- [x] rehydrating the stream produces the expected aggregate state
- [x] domain tests require no Spring context
- [x] cross-tenant reads cannot observe the stream

## Phase 2 — Publication, projections, and transactional outbox

**Goal:** publish an immutable revision reliably and expose command/read separation.

Scope:

- publish, withdraw, and restore commands with lifecycle invariants
- synchronous Postgres query projections for article summary, revision, and indexing status
- outbox row written in the same transaction as each externally relevant event
- scheduled/lock-safe outbox publisher using Spring Kafka
- `GET /api/v1/articles/{id}` and status/admin query endpoints
- `Idempotency-Key` support for commands

Acceptance criteria:

- [x] event append, projection update, and outbox insert commit or roll back together
- [x] duplicate command keys return the original result
- [x] two publisher instances do not publish the same pending row concurrently
- [x] published integration envelopes contain tracing and version metadata
- [x] replay rebuilds projections into an empty schema

## Phase 3 — Asynchronous chunking and embedding

**Goal:** turn a published article revision into a searchable vector projection.

Scope:

- `embedding-worker` Kafka listener and pure-Kotlin chunking port
- fixed/token-aware chunker with overlap and deterministic chunk IDs
- Spring AI `EmbeddingModel`; Ollama is the default local provider
- Spring AI pgvector `VectorStore` adapter with tenant, locale, article, and revision metadata
- retry classification, backoff, DLT, consumer inbox, and index-status events
- replacement of an old article revision without stale searchable chunks

Acceptance criteria:

- [x] publication produces searchable chunks for exactly one revision
- [x] redelivery does not duplicate chunks or status updates
- [x] transient model failure retries; permanent bad input reaches the DLT
- [x] withdrawal makes all article chunks ineligible for retrieval
- [x] a deterministic fake `EmbeddingModel` covers the normal CI path

## Phase 4 — Retrieval over internal REST

**Goal:** expose tenant-safe, query-optimized retrieval without gRPC.

Scope:

- `SearchKnowledge` application port in `retrieval-service`
- `POST /internal/v1/search` with a versioned request/response contract
- semantic search first, followed by Postgres full-text/vector hybrid ranking
- required tenant and optional collection/locale filters
- score threshold, bounded `topK`, deduplication, and citation metadata
- Spring HTTP Service client contract and integration/contract tests

Acceptance criteria:

- [x] known fixtures return correctly ranked, attributable chunks
- [x] low-relevance results are excluded
- [x] tenant isolation is tested at the vector query adapter
- [x] OpenAPI contract validation protects client/server compatibility
- [x] HTTP timeouts and problem responses are explicit

## Phase 5 — Grounded Q&A and conversation persistence

**Goal:** answer a help-center question with verifiable citations.

Scope:

- `POST /api/v1/questions` in `qa-service`
- retrieval client implemented with `RestClient` and a Spring HTTP Service interface
- Spring AI `ChatClient`, bounded prompt template, and structured output
- citation validation against retrieved chunk IDs
- `ANSWERED`, `INSUFFICIENT_CONTEXT`, and `MODEL_UNAVAILABLE` outcomes
- MongoDB conversation/turn persistence and Redis cache

Acceptance criteria:

- [ ] a seeded question returns an answer whose citations resolve to source revisions
- [ ] empty/weak retrieval never calls the chat model
- [ ] invented citation IDs fail validation and are not returned as grounded answers
- [ ] cache key includes tenant, corpus revision, locale, model, and prompt version
- [ ] deterministic fake models make CI independent of external AI services

## Phase 6 — Security, gateway, and multi-tenancy

**Goal:** make the public boundary production-shaped.

Scope:

- Spring Cloud Gateway routes and Redis-backed rate limiting
- OAuth2 resource-server configuration at gateway and public services
- roles for readers, editors, and administrators
- trusted tenant claim propagation and method authorization
- internal endpoint isolation, Kubernetes NetworkPolicy design, and CORS policy
- local Keycloak/test-token profile only if it improves the demo workflow

Acceptance criteria:

- [ ] unauthenticated public calls return `401`; insufficient roles return `403`
- [ ] a client cannot select another tenant through a header or body field
- [ ] internal retrieval routes are absent from public gateway routing
- [ ] rate limits distinguish tenant/client identity and return useful headers
- [ ] security integration tests use signed JWTs

## Phase 7 — Resilience and delivery hardening

**Goal:** make failure behavior intentional and observable.

Scope:

- timeouts, bulkheads, and circuit breakers around retrieval and model providers
- Kafka retry/DLT recovery endpoints and operational runbook
- reconciliation jobs for outbox, projection, and vector-index drift
- payload limits, content hashing, malware-scanning port for future file ingestion
- graceful shutdown and startup/readiness behavior

Acceptance criteria:

- [ ] model outage produces a bounded fallback response without thread exhaustion
- [ ] recovery from DLT is idempotent and audited
- [ ] reconciliation detects and repairs a missing vector projection
- [ ] resilience metrics expose circuit and retry state

## Phase 8 — Observability and RAG evaluation

**Goal:** make both system health and answer quality inspectable.

Scope:

- Micrometer metrics and observations in every service
- W3C trace propagation across Gateway, HTTP clients, and Kafka headers
- JSON logs to Loki, traces to Tempo, metrics to Prometheus, Grafana dashboards/alerts
- dashboards for article pipeline, Kafka lag, retrieval, AI use, and HTTP golden signals
- offline fixture set measuring retrieval recall and citation correctness

Acceptance criteria:

- [ ] one correlation ID finds the HTTP/Kafka/indexing path in traces and logs
- [ ] dashboards are provisioned from version-controlled files
- [ ] no high-cardinality domain identifiers are metric tags
- [ ] a documented command produces a repeatable evaluation report

## Phase 9 — Containers and Kubernetes

**Goal:** deploy the complete backend to a local Kubernetes cluster.

Scope:

- reproducible OCI images via Jib with non-root runtime and SBOM/provenance in CI
- umbrella Helm chart replacing provisional per-service fragments
- Deployments, Services, Ingress, ConfigMaps, Secrets references, NetworkPolicies, PDBs, and service accounts
- probes, resources, topology constraints, and KEDA/HPA policies
- dependencies installed through operators/upstream charts; Grafana stack integration

Acceptance criteria:

- [ ] `helm lint` and template validation pass in CI
- [ ] a clean kind cluster runs the seeded end-to-end scenario
- [ ] only Gateway is externally reachable
- [ ] worker scaling responds to Kafka lag, with a documented CPU fallback
- [ ] pods restart safely during an in-flight ingestion scenario

## Phase 10 — Portfolio delivery

**Goal:** make the architecture easy to understand, run, and assess.

Scope:

- concise README quickstart and architecture diagram
- public help-center seed corpus with licensing notes
- cross-platform demo scripts for ingest, publish, wait for indexing, ask, and cite
- ADRs for service boundaries, event sourcing, REST, databases, and Kubernetes configuration
- threat model, operational runbook, API examples, and benchmark/evaluation results

Acceptance criteria:

- [ ] a fresh clone reaches a working demo using only the README
- [ ] the demo requires no paid API key
- [ ] architecture diagrams match runtime dependencies
- [ ] limitations and production gaps are explicit

## Deferred choices

These are intentionally not decided before measurements justify them: dedicated vector database, schema registry, service mesh, native images, reactive persistence, and SSE streaming. They should become separate ADR-backed issues rather than silent scope additions.
