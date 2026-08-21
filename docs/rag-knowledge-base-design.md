# RAG Help Center — Architecture

## 1. Purpose and boundaries

This backend-only portfolio project is a realistic help-center knowledge platform. It manages versioned articles, asynchronously indexes them, and answers questions with grounded citations. Its purpose is to demonstrate current Kotlin and Spring practices without adding a UI or distributing every use case into a separate service.

The project deliberately demonstrates:

- Kotlin 2.4 and Java 25 on Spring Boot 4.1
- Spring Data JPA, MongoDB, and Redis
- Spring Cloud Gateway and HTTP service clients
- Spring AI chat, embedding, and vector-store APIs
- Spring Security OAuth2 resource servers with tenant-aware authorization
- Spring for Apache Kafka, including retries, dead letters, and idempotent consumers
- Hexagonal architecture, CQRS, and a narrowly scoped event-sourced aggregate
- Kubernetes deployment and Grafana-stack observability

It does not attempt to be a generic CMS, ticketing system, identity provider, or model-training platform.

## 2. Architectural decisions

Detailed decisions are recorded in `docs/decisions/`. In particular, [ADR 0001](decisions/0001-event-time-and-validation-ownership.md) defines ownership of domain occurrence time, database recording time, identity generation, and validation constraints. [ADR 0002](decisions/0002-synchronous-projections-and-transactional-outbox.md) defines CQRS projection and integration-event consistency.
For the asynchronous indexing boundary, [ADR 0003](decisions/0003-publication-integration-event-carries-revision-snapshot.md) defines the replayable publication snapshot contract.
[ADR 0004](decisions/0004-embedding-worker-projection-and-idempotency.md) defines worker idempotency, revision replacement, and embedding-provider ownership.

### Five deployables, not seven

| Module | Responsibility | Primary interfaces |
|---|---|---|
| `gateway` | Public routing, JWT enforcement, rate limits, correlation context | HTTPS |
| `ingestion-service` | Article commands, revisions, publication lifecycle, event store, outbox, status/admin queries | HTTPS, Kafka producer |
| `embedding-worker` | Chunk published revisions, generate embeddings, update the vector index | Kafka consumer/producer |
| `retrieval-service` | Tenant-filtered hybrid retrieval and citation candidates | Internal HTTPS |
| `qa-service` | RAG orchestration, conversations, answer streaming, feedback | HTTPS, internal HTTP client |
| `domain-kernel` | Small pure-Kotlin types and policies that are genuinely shared | Library |

The former `admin-api` added a network boundary around the same article aggregate and database, so its use cases belong in `ingestion-service`. A standalone Config Server is also unnecessary on Kubernetes; versioned non-secret configuration remains in the Helm chart, while ConfigMaps, Secrets, and environment variables provide runtime configuration. Spring Cloud is demonstrated by Gateway, circuit breaking, and service-to-service HTTP rather than by infrastructure with no domain value.

### Hexagonal architecture per service

Each service grows package-by-feature, with layers inside a feature:

```text
org.raghc.<service>.<feature>/
  domain/          # aggregates, value objects, domain events, policies
  application/     # input ports/use cases, output ports, transactions
  adapter/in/      # HTTP or Kafka adapters
  adapter/out/     # persistence, Spring AI, HTTP, Kafka adapters
```

Domain code has no Spring annotations. `domain-kernel` is kept intentionally small; service-specific domain models must not leak into a shared-model monolith.

### REST instead of gRPC

`qa-service` calls `retrieval-service` through a versioned internal JSON API, initially `POST /internal/v1/search`. The request carries the tenant in `X-Tenant-Id` and may restrict results with a tenant-scoped `collectionId` and locale. The client is a Spring HTTP Service interface backed by `RestClient`. This choice keeps the demo operable with standard Kubernetes networking, security, tracing, contract tests, and command-line tools. The boundary can later be benchmarked before introducing a binary protocol.

Internal traffic is HTTP in local development and HTTPS/mTLS at the ingress or service-mesh layer in Kubernetes. APIs use RFC 9457 problem details and OpenAPI contracts. Network DTOs are owned by the adapter, not the domain.

### CQRS and event sourcing, with constrained scope

The `KnowledgeArticle` aggregate is event sourced because help-center revisions, publication, withdrawal, and restoration have meaningful history. Commands produce immutable events such as:

- `ArticleCreated`
- `ArticleContentRevised`
- `ArticlePublished`
- `ArticleWithdrawn`
- `ArticleRestored`

Events are appended to a Postgres `article_events` stream with optimistic concurrency. The same database transaction updates an outbox. A publisher sends integration events to Kafka; consumers must be idempotent. Event payloads are versioned and upcastable.

CQRS is explicit:

- command side: rebuilds an article aggregate from its event stream and appends events;
- query side: reads projections such as `article_summary`, `article_revision`, and `indexing_status` without loading aggregates;
- asynchronous read models: vector index and conversation analytics are derived from events.

Kafka is not the source-of-truth event store. It is the integration log. Chat sessions and operational configuration are not event sourced.

## 3. Data ownership

| Technology | Owner | Natural use |
|---|---|---|
| PostgreSQL | `ingestion-service` | article event streams, projections, outbox, tenant metadata |
| PostgreSQL + pgvector | `embedding-worker` writes; `retrieval-service` reads | chunks, embeddings, full-text/vector hybrid search |
| MongoDB | `qa-service` | variably shaped conversation transcripts, citations, model usage, feedback |
| Redis | `gateway`, `qa-service` | distributed rate limits, short-lived semantic-answer cache, idempotency keys |
| Kafka | integration infrastructure | durable delivery of versioned integration events; not treated as a database of record |

Database ownership is enforced at the schema and credential level. No service writes another service's tables. The vector-index schema is an intentional read-model contract shared between one projector and one query service; it is versioned by migrations owned with the indexing capability.

## 4. Core use cases

### Article lifecycle

1. An editor creates a draft article for a tenant and locale.
2. The editor revises content using an expected stream version.
3. Publication appends an event and an outbox record atomically.
4. The worker consumes the publication event, chunks the immutable revision, embeds it, replaces that revision's vector projection, and publishes `ArticleIndexed`.
5. Status queries expose publication and indexing state independently.
6. Withdrawal removes the revision from retrieval while preserving its audit history.

Commands support an `Idempotency-Key`. Concurrent edits return `409 Conflict`; invalid lifecycle transitions return problem details.

### Ask a question

1. The gateway authenticates the caller and establishes tenant context.
2. `qa-service` validates the question and calls the internal retrieval API.
3. `retrieval-service` embeds the query and performs tenant/locale/collection-filtered hybrid search.
4. `qa-service` constructs a bounded prompt, asks the configured chat model, and rejects unsupported citations.
5. The answer, source citations, model metadata, latency, and feedback state are stored in MongoDB.
6. The API returns JSON initially; SSE token streaming is a later, compatible endpoint.

The answer contract distinguishes `ANSWERED`, `INSUFFICIENT_CONTEXT`, and `MODEL_UNAVAILABLE`. The service must prefer an honest insufficient-context result over an ungrounded answer.

## 5. Events and delivery guarantees

Topics use a stable namespace, for example:

- `help-center.article-publication.v1`
- `help-center.article-index-status.v1`
- `help-center.article-publication.v1-dlt`

Each envelope contains `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`, `aggregateId`, `correlationId`, and `causationId`. Schemas begin as JSON validated by contract tests; a schema registry is an optional later enhancement.

Delivery is at least once across the database/Kafka boundary. The transactional outbox prevents lost publication events. Unique consumer-inbox keys and replacement-by-revision indexing make processing idempotent. Spring Kafka transactions are used where a consume-process-produce sequence benefits from exactly-once semantics, but they do not replace database idempotency.

## 6. Security

- The gateway and public services are OAuth2 resource servers validating JWTs from an external OIDC provider.
- Roles include `helpcenter.reader`, `helpcenter.editor`, and `helpcenter.admin`.
- Tenant identity comes from a trusted claim and is passed internally in a signed token, not a caller-controlled header.
- Method authorization protects commands; persistence and vector queries always include tenant predicates.
- Internal endpoints are not exposed by the public ingress. NetworkPolicy limits callers.
- Secrets never live in Git, images, ConfigMaps, logs, or event payloads.

Local development may use a disposable identity-provider container or test JWTs, but production-shaped manifests never enable a permit-all profile.

## 7. Observability and operations

All deployables expose health and Prometheus actuator endpoints and emit structured JSON logs. Micrometer Observation propagates W3C trace context across HTTP and Kafka. OpenTelemetry exports traces to Tempo; logs flow to Loki; Prometheus scrapes metrics; Grafana provides dashboards and alerts.

Required signals include command/outbox lag, Kafka consumer lag and DLT counts, indexing duration, embedding/model latency, retrieval score distribution, insufficient-context rate, cache hit ratio, token usage, error rate, and saturation. Tenant or article IDs must not become unbounded metric labels.

Readiness does not depend on optional AI providers being healthy. Liveness never checks external dependencies. Graceful shutdown stops Kafka intake before terminating in-flight work.

## 8. Deployment

- Maven produces reproducible jars and OCI images through Jib.
- One umbrella Helm chart owns application deployments, services, ingress, NetworkPolicies, service accounts, HPAs, PodDisruptionBudgets, and configuration.
- PostgreSQL, MongoDB, Kafka, and the Grafana stack are Compose dependencies locally. Kubernetes uses operators or upstream charts; production databases are expected to be managed services.
- Kubernetes service DNS provides discovery. ConfigMaps/Secrets provide configuration. No Eureka or Config Server is deployed.
- `embedding-worker` scales primarily on Kafka lag; `qa-service` scales on concurrency/latency. CPU-only HPA is a fallback.

The existing per-service Helm fragments are scaffolding only and are replaced by the umbrella chart during the deployment phase.

## 9. Testing strategy

- Pure unit tests for aggregates, chunking, prompt policies, and use cases.
- ArchUnit/Konsist rules for dependency direction.
- Spring slice tests for HTTP, persistence, Kafka, and security adapters.
- Testcontainers integration tests for PostgreSQL/pgvector, Kafka, MongoDB, and Redis.
- Consumer-driven or OpenAPI contract tests for the retrieval HTTP boundary.
- End-to-end tests use deterministic fake embedding/chat adapters by default; a tagged test exercises Ollama.
- k6 scenarios cover ingestion throughput and Q&A latency. Evaluation fixtures measure retrieval recall and grounded citation correctness.

## 10. Deliberate non-goals and later options

- No separate service per entity, Eureka, distributed transaction coordinator, or shared writable database.
- No event sourcing outside the article lifecycle without a demonstrated audit/replay requirement.
- No cached answer without tenant, corpus revision, locale, model, and prompt version in the cache key.
- Qdrant/OpenSearch, schema registry, service mesh, native images, and SSE are extensions after the end-to-end path is measured.
