# RAG Knowledge Base — Implementation Plan

This plan turns the roadmap from the design doc into a sequence of GitHub issues
and matching PR descriptions. Create the issues in order; each PR should
reference and close its issue (`Closes #N`).

---

## 0. Project Setup (do this before creating the GitHub project)

1. Create a new empty GitHub repository (no template) — e.g. `rag-help-center`
2. Locally, scaffold a **multi-module Gradle project using the Kotlin DSL**:
   ```
   rag-knowledge-base/
     settings.gradle.kts
     build.gradle.kts              (root — shared plugin/version config)
     gradle/libs.versions.toml     (version catalog)
     domain-kernel/                (pure Kotlin, no Spring deps)
     ingestion-service/
     embedding-worker/
     retrieval-service/
     qa-service/
     admin-api/
     gateway/
     config-server/
     docker-compose.yml
     .github/workflows/ci.yml
   ```
3. Set up `docker-compose.yml` with: Postgres (with `pgvector` extension image),
   Redis, Kafka (+ Zookeeper or KRaft mode), and the Grafana stack (Prometheus,
   Loki, Tempo, Grafana) — this can start minimal and grow in Phase 8
4. Add a root `.editorconfig`, `ktlint`/`detekt` config for consistent Kotlin
   style
5. Push the initial scaffold as the first commit on `main` directly (no issue/PR
   needed for this bootstrap commit — everything after this goes through
   issues/PRs)
6. Create the GitHub Project (board) and add labels: `phase-0` … `phase-10`,
   `type:feature`, `type:infra`, `type:docs`

Once this is done, create the issues below in order and work them one PR at a time.

---

## Phase 0 — Foundations

### GitHub Issue

**Title:** `Phase 0: Repository scaffolding, local infra, and CI skeleton`

## Goal

Set up the multi-module Gradle project structure, local docker-compose
infrastructure, and a minimal CI pipeline before any feature work begins.

## Scope

- Multi-module Gradle (Kotlin DSL) project with a `domain-kernel` module and
  empty placeholder modules for each service (`ingestion-service`,
  `embedding-worker`, `retrieval-service`, `qa-service`, `admin-api`, `gateway`,
  `config-server`)
- `docker-compose.yml` with Postgres (pgvector-enabled image), Redis, Kafka
- GitHub Actions workflow: build + unit test on push/PR
- Code style: ktlint/detekt configured at the root

## Out of scope
- Any actual business logic
- Kubernetes manifests (Phase 9)
- Grafana stack wiring (Phase 8, though the compose file can reserve the services)

## Acceptance Criteria
- [ ] `./gradlew build` succeeds with all empty modules
- [ ] `docker-compose up` brings up Postgres, Redis, Kafka cleanly
- [ ] CI workflow runs on PR and passes
- [ ] README documents how to run the local environment

### Pull Request Description

## Summary

Bootstraps the repository: multi-module Gradle structure, local docker-compose
infra (Postgres+pgvector, Redis, Kafka), and a CI skeleton.

Closes #1

## Changes

- Added multi-module Gradle project (Kotlin DSL) with `domain-kernel` and
  placeholder service modules
- Added `docker-compose.yml` for local Postgres (pgvector), Redis, Kafka
- Added `.github/workflows/ci.yml` running build + test on push/PR
- Added ktlint/detekt configuration at the root
- Added README with local setup instructions

## How to test

1. `docker-compose up -d`
2. `./gradlew build`
3. Confirm CI passes on this PR

## Notes / judgment calls

- Chose Kafka in KRaft mode (no Zookeeper) to keep the compose file simpler —
  flag if you'd prefer Zookeeper-based for closer parity with typical prod
  setups
- Grafana stack services are not yet in docker-compose; deferred to Phase 8 to
  avoid an oversized compose file early on

---

## Phase 1 — Ingestion Walking Skeleton

### GitHub Issue

**Title:** `Phase 1: Ingestion service walking skeleton`

## Goal

Stand up `ingestion-service` end-to-end: accept a document, persist metadata,
publish an event — no embedding yet.

## Scope

- REST endpoint `POST /documents` accepting text (file upload can follow later)
  and basic metadata
- Postgres schema + Spring Data JPA entities: `documents`, `document_status`
- Pure-domain chunker in `domain-kernel` (fixed-size strategy), unit tested
  without Spring
- Publish `document.received` Kafka event on successful ingestion
- `GET /documents/{id}/status` endpoint

## Out of scope

- Embedding/indexing (Phase 2)
- Auth (Phase 5)

## Acceptance Criteria

- [ ] Can POST a document and receive a document ID
- [ ] Document + status row visible in Postgres
- [ ] `document.received` event observable on the Kafka topic
- [ ] Chunker has unit tests covering edge cases (empty doc, doc smaller than
  chunk size, exact multiple)
- [ ] Status endpoint returns `RECEIVED`/`CHUNKED`

### Pull Request Description

## Summary

Implements the ingestion walking skeleton: upload → persist → chunk → publish
event, following the hexagonal structure (domain in `domain-kernel`, adapters in
`ingestion-service`).

Closes #2

## Changes

- `domain-kernel`: `Document`, `Chunk` domain models, `FixedSizeChunker` with
  unit tests
- `ingestion-service`: REST controller, JPA entities/repositories, Kafka
  producer for `document.received`
- Flyway migration for `documents` / `document_status` tables
- Status tracking: `RECEIVED → CHUNKED`

## How to test

1. `POST /documents` with sample text via curl/Postman
2. Check Postgres `documents` table for the new row
3. Check the `document.received` topic (e.g. via `kafka-console-consumer`) for
   the event
4. `GET /documents/{id}/status` returns `CHUNKED`

## Notes / judgment calls

- Used Flyway for migrations (not in the original design doc) — flag if you'd
  prefer Liquibase instead
- File upload deferred; only raw text body supported for now, to keep this PR
  focused

---

## Phase 2 — Embedding & Indexing

### GitHub Issue

**Title:** `Phase 2: Embedding worker and pgvector indexing`

## Goal

Consume ingestion events, generate embeddings via Spring AI, and index them in
pgvector.

## Scope

- `embedding-worker`: Kafka listener on `document.received`
- Spring AI `EmbeddingClient` integration (configurable provider, start with a
  local/cheap model)
- pgvector schema for chunk embeddings + Spring Data repository
- Publish `document.indexed` on success; dead-letter topic + retry on failure
- Status transitions: `CHUNKED → EMBEDDING → INDEXED` / `FAILED`

## Out of scope

- Retrieval/search API (Phase 3)

## Acceptance Criteria

- [ ] Ingesting a document results in vectors present in pgvector
- [ ] Status correctly transitions through `EMBEDDING → INDEXED`
- [ ] Simulated embedding failure routes to dead-letter topic and sets status
  `FAILED`
- [ ] Retry policy configurable (max attempts, backoff)

### Pull Request Description

## Summary

Adds `embedding-worker`, which consumes ingestion events, embeds chunks via
Spring AI, and writes vectors to pgvector.

Closes #3

## Changes

- `embedding-worker`: Kafka consumer, Spring AI `EmbeddingClient` wiring
- pgvector schema migration + repository for chunk vectors
- Retry + dead-letter handling for failed embedding calls
- Status transitions wired into `ingestion-service`'s status table (via a
  status-update event/topic)

## How to test

1. Ingest a document (Phase 1 endpoint)
2. Confirm vectors appear in the `chunk_embeddings` table
3. Check status endpoint reaches `INDEXED`
4. Force a failure (e.g. invalid API key) and confirm dead-letter routing +
   `FAILED` status

## Notes / judgment calls

- Chose [local model / OpenAI — confirm which] as the default embedding provider
  for local dev to avoid requiring API keys out of the box
- Status updates flow back to `ingestion-service` via a dedicated
  `document.status-changed` topic rather than a direct DB write from
  `embedding-worker`, to keep services decoupled — flag if a shared status table
  is preferred instead

---

## Phase 3 — Retrieval Service

### GitHub Issue

**Title:** `Phase 3: Retrieval service with gRPC search API`

## Goal

Build `retrieval-service` as a hexagonal core exposing a `SearchDocuments` use
case over gRPC.

## Scope

- Spring gRPC server: `Search(query: String, topK: Int) -> List<ScoredChunk>`
- Application layer: `SearchDocumentsUseCase` (pure, testable) + port for the
  vector store
- Adapter: pgvector similarity search implementation of the port
- Basic integration test hitting the gRPC endpoint

## Out of scope

- Q&A orchestration / LLM calls (Phase 4)

## Acceptance Criteria

- [ ] gRPC call returns ranked chunks with similarity scores for a known query
- [ ] Use case is unit-testable with a fake vector store port (no Spring context
  needed)
- [ ] Integration test spins up the gRPC server and asserts on a real query
  against seeded data

### Pull Request Description

## Summary

Adds `retrieval-service`: hexagonal core with a `SearchDocuments` use case,
exposed via a Spring gRPC server, backed by pgvector similarity search.

Closes #4

## Changes

- `.proto` definition for the `Search` RPC
- `retrieval-service`: gRPC server adapter, pgvector-backed `VectorSearchPort`
  implementation
- Domain-level `SearchDocumentsUseCase` with unit tests using a fake port
- Integration test against a seeded pgvector dataset

## How to test

1. Seed pgvector with a few known chunks (test fixture)
2. Call the gRPC `Search` method with a query related to the seeded content
3. Confirm results are ranked sensibly by similarity score

## Notes / judgment calls

- `topK` defaults to 5, configurable via request — confirm this default is
  reasonable
- No auth on the gRPC endpoint yet (internal-only for now); addressed in Phase 5

---

## Phase 4 — Q&A Orchestration

### GitHub Issue

**Title:** `Phase 4: Q&A service — RAG orchestration endpoint`

## Goal

Expose a public `/ask` endpoint that retrieves relevant chunks and generates a
cited answer via an LLM.

## Scope

- `qa-service`: REST endpoint `POST /ask`
- gRPC client to `retrieval-service`
- Prompt construction (question + retrieved chunks)
- Spring AI `ChatClient` call to generate the answer
- Response includes answer text + citations (source chunk IDs/snippets)
- Redis caching of responses keyed by query hash

## Out of scope

- Auth/multi-tenancy (Phase 5)
- Streaming responses (stretch extension)

## Acceptance Criteria

- [ ] `/ask` returns an answer with at least one citation for a question covered
  by the seeded corpus
- [ ] Repeated identical queries hit the Redis cache (verified via cache
  metrics/logs)
- [ ] Prompt template is externalized/configurable, not hardcoded inline

### Pull Request Description

## Summary

Adds `qa-service` with the `/ask` endpoint, orchestrating retrieval (via gRPC)
and LLM generation (via Spring AI `ChatClient`), with Redis response caching.

Closes #5

## Changes

- `qa-service`: REST controller, gRPC client to `retrieval-service`
- Prompt template (externalized as a resource file) combining question +
  retrieved chunks
- Spring AI `ChatClient` integration
- Redis-backed response cache keyed by query hash
- Citation mapping from retrieved chunks to the response payload

## How to test

1. `POST /ask` with a question covered by the seeded corpus
2. Confirm response includes an answer and citations pointing to real chunks
3. Repeat the same request and confirm a cache hit (check logs/metrics)

## Notes / judgment calls

- Cache TTL set to [X minutes] by default — flag if this should be configurable
  per environment from the start
- Citations reference chunk ID + a short snippet, not the full chunk text, to
  keep responses compact

---

## Phase 5 — Security & Multi-Tenancy

### GitHub Issue

**Title:** `Phase 5: OAuth2 security and tenant scoping`

## Goal

Secure all public-facing endpoints with OAuth2/JWT and scope data access by
tenant.

## Scope

- Spring Security OAuth2 resource server config on `ingestion-service`,
  `qa-service`, `admin-api`
- `tenant_id` column added to documents/chunks; enforced on all reads/writes
- JWT claim → tenant mapping
- Rate limiting groundwork (Redis-backed counter) — full enforcement lands in
  Phase 6 at the gateway

## Out of scope

- Gateway-level enforcement (Phase 6)

## Acceptance Criteria

- [ ] Unauthenticated requests to protected endpoints return 401
- [ ] A JWT for tenant A cannot read tenant B's documents (integration test)
- [ ] `tenant_id` is enforced at the repository/query level, not just the
  controller

### Pull Request Description

## Summary

Adds OAuth2/JWT-based security to public-facing services and enforces tenant
scoping on document data.

Closes #6

## Changes

- Spring Security resource server config (JWT validation) on
  `ingestion-service`, `qa-service`, `admin-api`
- `tenant_id` migration + enforcement in repository queries
- Integration tests verifying cross-tenant access is blocked

## How to test

1. Call a protected endpoint without a token → expect 401
2. Call with a valid token for tenant A, ingest a document
3. Call with a token for tenant B, confirm tenant A's document is not
   visible/searchable

## Notes / judgment calls

- Used a local JWT issuer for tests/dev (e.g. a test auth server) rather than
  requiring a real IdP — document this clearly so it's not mistaken for
  production-ready auth

---

## Phase 6 — Gateway & Cloud Config

### GitHub Issue

**Title:** `Phase 6: Spring Cloud Gateway and centralized configuration`

## Goal

Route all public traffic through Spring Cloud Gateway and centralize
configuration via Spring Cloud Config Server.

## Scope

- `gateway`: routes to `qa-service`, `ingestion-service`, `admin-api`
- Auth enforcement moved/duplicated at the gateway layer (reject before hitting
  services)
- Rate limiting at the gateway (Redis-backed, from Phase 5 groundwork)
- `config-server`: centralized config for all services, backed by a git-based
  config repo (can be a subfolder of this repo initially)

## Out of scope

- Kubernetes-native config (ConfigMaps/Secrets) — Phase 9

## Acceptance Criteria

- [ ] All external traffic goes through the gateway; direct service calls are no
  longer required for normal usage
- [ ] Rate limiting demonstrably rejects requests over the configured threshold
- [ ] All services pull their config from `config-server` at startup

### Pull Request Description

## Summary

Adds `gateway` (Spring Cloud Gateway) as the single entry point for public
traffic, and `config-server` for centralized configuration.

Closes #7

## Changes

- `gateway`: route definitions for `qa-service`, `ingestion-service`,
  `admin-api`
- Redis-backed rate limiter at the gateway
- `config-server` with a git-backed config repo; all services migrated to pull
  config from it
- Updated docker-compose to reflect new startup order/dependencies

## How to test

1. Bring up the full stack via docker-compose
2. Verify requests to service ports directly still work (internal) but the
   intended path is via the gateway port
3. Exceed the rate limit and confirm 429 responses
4. Change a config value in the config repo and confirm a service picks it up on
   restart/refresh

## Notes / judgment calls

- Config Server backed by a local git repo folder for simplicity in this
  project, rather than a separate remote repo — call out in README as a
  simplification vs. a real multi-repo setup

---

## Phase 7 — Resilience & Hardening

### GitHub Issue

**Title:** `Phase 7: Resilience patterns and load testing`

## Goal

Add circuit breakers/timeouts around external calls and validate the system
under load.

## Scope

- Resilience4j circuit breaker + timeout around Spring AI embedding and chat
  calls
- Idempotent ingestion: dedupe by content hash
- k6 load test scripts for `/documents` (ingestion) and `/ask` (Q&A)
- Document baseline performance numbers

## Out of scope

- Full observability dashboards (Phase 8) — basic metrics only for now

## Acceptance Criteria

- [ ] Simulated LLM timeout triggers the circuit breaker and returns a graceful
  error, not a hung request
- [ ] Re-ingesting identical content is detected and skipped (or returns the
  existing document ID)
- [ ] k6 scripts run against a local environment and produce a baseline report
  committed to the repo

### Pull Request Description

## Summary

Adds resilience patterns (circuit breaker, timeout) around AI calls, idempotent
ingestion, and k6 load tests with a documented baseline.

Closes #8

## Changes

- Resilience4j config for embedding/chat client calls
- Content-hash-based dedupe on ingestion
- k6 scripts: `k6/ingestion.js`, `k6/ask.js`
- `docs/performance-baseline.md` with initial results

## How to test

1. Simulate an LLM provider outage/timeout, confirm circuit breaker opens and
   `/ask` fails gracefully
2. POST the same document content twice, confirm dedupe behavior
3. Run `k6 run k6/ask.js` locally and review the summary output

## Notes / judgment calls

- Circuit breaker thresholds set to [defaults] — these are starting points, not
  tuned; call this out in the doc

---

## Phase 8 — Observability

### GitHub Issue

**Title:** `Phase 8: Metrics, logs, traces, and Grafana dashboards`

## Goal

Instrument all services with Micrometer/OpenTelemetry and wire up the Grafana
stack.

## Scope

- Micrometer metrics on all services (ingestion throughput, embedding latency,
  retrieval latency, LLM latency/cost, cache hit ratio)
- OpenTelemetry tracing across HTTP/gRPC/Kafka boundaries, exported to Tempo
- Structured JSON logging with correlation ID propagation, shipped to Loki
- Add Prometheus, Loki, Tempo, Grafana to docker-compose
- Build Grafana dashboards: pipeline health, RAG latency, LLM cost

## Out of scope

- k8s-native observability deployment (Phase 9 uses Helm for this)

## Acceptance Criteria

- [ ] A single `/ask` request produces a trace spanning gateway → qa-service →
  retrieval-service (gRPC) → pgvector, and → embedding-worker's earlier work is
  separately traceable
- [ ] Correlation ID present in logs across at least 3 services for one request
- [ ] Grafana dashboards imported and showing live data from the local stack

### Pull Request Description

## Summary

Instruments all services with metrics, logs, and traces, and adds Grafana
dashboards for pipeline health, RAG latency, and LLM cost.

Closes #9

## Changes

- Micrometer + OpenTelemetry starter added to all services
- Structured logging config with correlation ID propagation (MDC +
  header/Kafka-header passthrough)
- Prometheus, Loki, Tempo, Grafana added to docker-compose
- `grafana/dashboards/*.json` — pipeline health, RAG latency, LLM cost
  dashboards, auto-provisioned

## How to test

1. Bring up the full stack via docker-compose
2. Make an `/ask` request, then find its trace in Tempo (via Grafana) spanning
   all involved services
3. Search Loki for the request's correlation ID and confirm logs from multiple
   services appear
4. Open the provisioned Grafana dashboards and confirm panels populate

## Notes / judgment calls

- LLM "cost" metric is estimated from token counts using a configurable
  price-per-token, not billed amounts — documented as an approximation

---

## Phase 9 — Kubernetes Deployment

### GitHub Issue

**Title:** `Phase 9: Kubernetes manifests and Helm chart`

## Goal

Package and deploy the full system to Kubernetes, including the Grafana stack.

## Scope

- Dockerfiles for all services (multi-stage builds)
- Helm chart covering Deployments, Services, ConfigMaps, Secrets for all
  services
- HPA for `embedding-worker` and `qa-service`
- Deploy Grafana stack via `kube-prometheus-stack` + Loki + Tempo Helm charts
- Ingress for the gateway

## Out of scope

- Multi-cluster/production-grade secret management (documented as a future
  concern)

## Acceptance Criteria

- [ ] `helm install` brings up the full system on a local cluster
  (kind/minikube)
- [ ] `/ask` is reachable via Ingress and returns correct results
- [ ] HPA scales `embedding-worker` under simulated load
- [ ] Grafana dashboards from Phase 8 are reachable and populated in-cluster

### Pull Request Description

## Summary

Adds Dockerfiles and a Helm chart to deploy the entire system, including the
Grafana observability stack, to Kubernetes.

Closes #10

## Changes

- Multi-stage Dockerfiles per service
- Helm chart: `charts/rag-knowledge-base/` with subcharts/values for each
  service
- HPA configuration for `embedding-worker`, `qa-service`
- Values files wiring up `kube-prometheus-stack`, Loki, Tempo as dependencies
- Ingress definition for the gateway

## How to test

1. `kind create cluster` (or minikube)
2. `helm install rag-kb ./charts/rag-knowledge-base`
3. Port-forward/Ingress to reach the gateway, run the same `/ask` smoke test as
   Phase 8
4. Generate load and observe HPA scaling `embedding-worker` replicas

## Notes / judgment calls

- Secrets (DB credentials, AI provider keys) use plain k8s Secrets for this
  portfolio project, not a vault integration — explicitly flagged in README as a
  simplification, not a production pattern

---

## Phase 10 — Polish & Showcase

### GitHub Issue

**Title:** `Phase 10: Documentation, seed dataset, and demo`

## Goal

Make the project easy to evaluate: clear docs, a seed dataset, and a runnable
demo script.

## Scope

- Top-level README: architecture diagram, CQRS/no-event-sourcing rationale,
  module map, how to run locally and on k8s
- Seed dataset (small public-domain corpus) + ingestion script
- Demo script: ingest corpus, ask a set of sample questions, show results
- Optional: swap pgvector → Qdrant behind the same port to demonstrate hexagonal
  swappability (separate follow-up issue if time-boxed out)

## Acceptance Criteria

- [ ] A new reader can go from clone to a working `/ask` demo using only the
  README
- [ ] Architecture diagram present and accurate
- [ ] Demo script runs end-to-end without manual intervention

### Pull Request Description

## Summary

Finalizes documentation and adds a seed dataset + demo script so the project is
easy to evaluate end-to-end.

Closes #11

## Changes

- Rewrote top-level README with architecture diagram and rationale for
  architectural choices
- `demo/seed-corpus/` with sample documents
- `demo/run-demo.sh` — ingests the corpus and runs a set of sample questions
  against `/ask`
- Cross-links to phase-by-phase docs for anyone wanting implementation detail

## How to test

1. Fresh clone
2. Follow README setup steps only
3. Run `demo/run-demo.sh` and confirm sensible answers with citations

## Notes / judgment calls

- Seed corpus kept intentionally small (~10 documents) to keep the demo fast;
  noted in README how to point it at a larger corpus
