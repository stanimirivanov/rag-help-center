# AI-Powered Knowledge Base / RAG Pipeline — Detailed Design

## 1. Overview

A backend-only, Kotlin/Spring reference system that ingests documents, embeds
and indexes them, and serves retrieval-augmented generation (RAG) Q&A over the
corpus. It demonstrates the modern Spring ecosystem (Boot, Data, Cloud, AI,
gRPC, Security, Kafka), Hexagonal architecture, a lightweight CQRS split,
Kubernetes deployment, and Grafana-stack observability.

**Goals**

- Real, working RAG pipeline (not a toy) with pluggable embedding/LLM providers
  via Spring AI
- Clean separation between ingestion (write path) and retrieval/Q&A (read path)
- Multiple database technologies used for genuinely different purposes, not
  decoration
- Production-shaped deployment: k8s manifests/Helm, observability, resilience

---

## 2. Bounded Contexts / Modules

| Module              | Responsibility                                                                                      |
|---------------------|-----------------------------------------------------------------------------------------------------|
| `ingestion-service` | Accepts documents (files/URLs/text), chunks, extracts metadata, publishes `DocumentIngested` events |
| `embedding-worker`  | Consumes ingestion events, generates embeddings via Spring AI, writes to vector store               |
| `retrieval-service` | Hexagonal core exposing search/query use cases; internal gRPC API for other services                |
| `qa-service`        | Public-facing API; orchestrates retrieval + LLM completion (RAG orchestration)                      |
| `admin-api`         | Corpus management, tenant/collection admin, re-indexing triggers                                    |
| `gateway`           | Spring Cloud Gateway — routing, auth enforcement, rate limiting                                     |
| `config-server`     | Spring Cloud Config — centralized config                                                            |

Each service follows **Hexagonal Architecture**: `domain` (pure Kotlin, no
framework deps) → `application` (use cases/ports) → `adapters` (in:
REST/gRPC/Kafka listener; out: JPA repo, vector store client, Spring AI client).

---

## 3. Architecture Style

- **CQRS (lightweight, not full ES):**
    - **Write side:** ingestion pipeline — documents, chunks, embedding jobs
      (Postgres, transactional)
    - **Read side:** denormalized/query-optimized views for search results and
      chat history (vector store + Redis cache)
    - No event sourcing for the domain state itself (would be over-engineering
      here); Kafka is used as an **integration/streaming backbone**, not an
      event store — this is a deliberate, explainable architectural choice worth
      documenting in the README
- **Saga-lite:** ingestion → chunking → embedding → indexing is a simple
  choreographed pipeline via Kafka topics, with a status field per document
  (`RECEIVED → CHUNKED → EMBEDDING → INDEXED → FAILED`) tracked in Postgres for
  observability and retry

---

## 4. Tech Stack Mapping

| Concern                  | Technology                                                                                |
|--------------------------|-------------------------------------------------------------------------------------------|
| Core framework           | Spring Boot 3.x, Kotlin                                                                   |
| Persistence (metadata)   | Spring Data JPA + Postgres                                                                |
| Vector store             | Spring AI `VectorStore` abstraction over **pgvector**                                     |
| Caching / query results  | Redis (Spring Data Redis)                                                                 |
| Streaming                | Spring for Apache Kafka (ingestion pipeline, status events)                               |
| AI / embeddings / LLM    | Spring AI (`EmbeddingClient`, `ChatClient`) — pluggable OpenAI/local model (e.g., Ollama) |
| Internal RPC             | Spring gRPC — `qa-service` → `retrieval-service`                                          |
| Gateway/discovery/config | Spring Cloud Gateway, Config Server, (optional) Eureka or k8s-native discovery            |
| Security                 | Spring Security (OAuth2 resource server, JWT), per-tenant scoping                         |
| Resilience               | Resilience4j (circuit breaker on LLM calls)                                               |
| Observability            | Micrometer + OpenTelemetry → Prometheus, Loki, Tempo, Grafana dashboards                  |
| Deployment               | Docker, Kubernetes manifests + Helm chart                                                 |

---

## 5. Data Flow

1. Client uploads a document → `ingestion-service` (REST) stores raw metadata in
   Postgres, publishes `document.received` to Kafka
2. `embedding-worker` consumes → chunks text (configurable strategy: fixed-size,
   semantic) → calls Spring AI `EmbeddingClient` → writes vectors + chunk
   metadata to pgvector → publishes `document.indexed`
3. Client asks a question → `qa-service` (REST) → calls `retrieval-service` via
   **gRPC** with the query → retrieval-service embeds the query, performs
   similarity search against pgvector, returns top-k chunks
4. `qa-service` builds a prompt with retrieved context → Spring AI
   `ChatClient` → LLM response → cached in Redis (keyed by query hash) →
   returned to client, with citations to source chunks

---

## 6. Database Usage (each with a real reason)

- **Postgres**: transactional metadata — documents, chunks, ingestion status,
  tenants, users
- **pgvector**: embeddings + similarity search (kept close to Postgres to start;
  could be swapped for a dedicated vector DB like Qdrant later as an explicit
  "V2" extension point)
- **Redis**: query-result caching, rate-limiting counters, idempotency keys for
  ingestion

---

## 7. Observability

- **Metrics** (Micrometer → Prometheus): ingestion throughput, embedding
  latency, retrieval latency, LLM call latency/cost, cache hit ratio
- **Logs** (Loki): structured JSON logs, correlation ID propagated across
  Kafka/gRPC/HTTP boundaries
- **Traces** (Tempo via OpenTelemetry): full trace from `qa-service` → gRPC →
  pgvector query → LLM call
- **Grafana dashboards**: pipeline health (documents by status), RAG quality
  proxy metrics (retrieval hit rate, latency percentiles), LLM cost tracking

---

## 8. Roadmap — Step-by-Step Implementation

### Phase 0 — Foundations

- Repo scaffolding (multi-module Gradle, Kotlin DSL), shared domain/kernel
  module
- Local docker-compose: Postgres+pgvector, Redis, Kafka, Grafana stack
- CI pipeline skeleton (build/test on push)

### Phase 1 — Ingestion Walking Skeleton

- `ingestion-service`: REST endpoint to upload text/file, persist to Postgres,
  publish Kafka event
- Basic chunking (fixed-size) in a pure-domain chunker (hexagonal core,
  unit-testable without Spring)
- Status tracking table + endpoint to check ingestion status

### Phase 2 — Embedding & Indexing

- `embedding-worker`: Kafka listener, Spring AI `EmbeddingClient` integration
  (start with a local/cheap model)
- pgvector schema + Spring Data repository for chunk vectors
- Publish `document.indexed`; add retry/dead-letter handling for failed
  embeddings

### Phase 3 — Retrieval Service

- `retrieval-service` hexagonal core: `SearchDocuments` use case
- Spring gRPC server exposing `Search(query, topK)`
- Similarity search against pgvector, return ranked chunks with scores

### Phase 4 — Q&A Orchestration

- `qa-service`: REST endpoint `/ask`, gRPC client to retrieval-service
- Prompt construction + Spring AI `ChatClient` call
- Response caching in Redis, citation of source chunks in the response

### Phase 5 — Security & Multi-Tenancy

- Spring Security OAuth2 resource server (JWT) across all public-facing services
- Tenant/collection scoping on ingestion and retrieval (row-level filters)
- Rate limiting at the gateway (Redis-backed)

### Phase 6 — Gateway & Cloud Config

- Spring Cloud Gateway in front of `qa-service`, `ingestion-service`,
  `admin-api`
- Spring Cloud Config Server for centralized configuration across services

### Phase 7 — Resilience & Hardening

- Resilience4j circuit breakers/timeouts on LLM and embedding calls
- Idempotent ingestion (dedupe by content hash)
- Load testing (k6) of ingestion and Q&A endpoints — ties back to your existing
  perf-testing background

### Phase 8 — Observability

- Micrometer + OpenTelemetry instrumentation across all services
- Prometheus scraping, Loki log shipping, Tempo tracing
- Build Grafana dashboards (pipeline health, RAG latency, cost)

### Phase 9 — Kubernetes Deployment

- Dockerfiles per service, Helm chart (or Kustomize) for the whole system
- k8s manifests: Deployments, Services, ConfigMaps/Secrets, HPA for
  `embedding-worker` and `qa-service`
- Deploy Grafana stack via Helm (kube-prometheus-stack, Loki, Tempo)

### Phase 10 — Polish & Showcase

- README with architecture diagrams and the CQRS/no-ES rationale
- Seed dataset + demo script (ingest a small corpus, ask sample questions)
- Optional: swap pgvector → Qdrant behind the same port/adapter to demonstrate
  hexagonal swappability

---

## 9. Stretch Extensions (post-MVP)

- Streaming LLM responses (SSE) from `qa-service`
- Hybrid search (keyword + vector) using Postgres full-text search alongside
  pgvector
- Feedback loop: thumbs up/down on answers stored in Postgres, surfaced in a
  Grafana panel
- Swap Kafka Streams in for the status-aggregation logic to also demonstrate
  stream processing
