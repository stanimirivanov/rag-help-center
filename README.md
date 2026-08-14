# RAG Help Center

A portfolio project demonstrating modern backend architecture patterns using
Kotlin + Spring ecosystem (Boot, Data, Cloud, AI, gRPC, Security, Kafka).

See [`docs/rag-knowledge-base-design.md`](docs/rag-knowledge-base-design.md)
for the full design and [`docs/rag-knowledge-base-implementation-plan.md`](docs/rag-knowledge-base-implementation-plan.md)
for the phase-by-phase implementation plan.

## Status

Project setup only (implementation plan, phase 0) — no business logic yet.
`domain-kernel` and each service module (`admin-api`, `config-server`,
`embedding-worker`, `gateway`, `ingestion-service`, `qa-service`,
`retrieval-service`) exist as empty, buildable Spring Boot placeholders.

## Modules and ports

| Module | Port | Role |
|---|---|---|
| `gateway` | 8080 | Public entry point (Phase 6: Spring Cloud Gateway) |
| `ingestion-service` | 8081 | Accepts documents, chunks, publishes ingestion events |
| `embedding-worker` | 8082 | Consumes ingestion events, writes embeddings to pgvector |
| `retrieval-service` | 8083 | Similarity search, internal gRPC API |
| `qa-service` | 8084 | Public `/ask` endpoint, RAG orchestration |
| `admin-api` | 8085 | Corpus/tenant administration |
| `config-server` | 8888 | Spring Cloud Config (8888 is its conventional default port) |
| `domain-kernel` | — | Pure Kotlin domain library, no Spring deps, no port |

## Prerequisites

- JDK 25 (the project targets Java 25, Spring Boot 4.1's recommended
  baseline — see `java.version` in the root `pom.xml`)
- Docker + Docker Compose, for local infra

## One-time setup: generate the Maven wrapper

This repo ships `.mvn/wrapper/maven-wrapper.properties` but not the `mvnw` /
`mvnw.cmd` scripts themselves — generating those requires running Maven
once with network access, which isn't available in the environment these
files were prepared in. If you have Maven installed locally, run this once
from the repo root and commit the result:

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.16
```

After that, use `./mvnw` (or `mvnw.cmd` on Windows) for everything below —
no local Maven install is required for anyone who clones the repo afterwards.

## Local development

Bring up local infra (Postgres with pgvector, Redis, Kafka in KRaft mode):

```bash
docker-compose up -d
```

Build everything:

```bash
./mvnw clean install
```

Run a single service against the infra above (from the repo root):

```bash
./mvnw -pl ingestion-service spring-boot:run -Dspring-boot.run.profiles=local
```

`application-local.yaml` in each service points at the Postgres instance
started by docker-compose. Kafka is reachable from the host at
`localhost:9092` (services running natively) and from other containers on
the same compose network at `kafka:19092` (for when services get
containerized here too, in a later phase).

## Code style

`./mvnw verify` runs ktlint and detekt in addition to tests. Format
violations: `./mvnw ktlint:format`.

## Container images

Images are built with [jib](https://github.com/GoogleContainerTools/jib) —
no Dockerfile needed. Each service's `pom.xml` activates the plugin and
sets its own container port; `jib:dockerBuild` builds to your local Docker
daemon, `jib:build` pushes to the registry.

Pushing to the configured registry (`fra.ocir.io`, OCI Registry) needs both
of the following:

- **Auth**: run `docker login fra.ocir.io` locally first (jib reads
  `~/.docker/config.json`), or set `JIB_TO_AUTH_USERNAME` /
  `JIB_TO_AUTH_PASSWORD` env vars in CI. Nothing in this repo stores
  credentials.
- **`OCIR_NAMESPACE`**: the root `pom.xml` builds the image path as
  `fra.ocir.io/${OCIR_NAMESPACE}/rag-help-center/<service>`, so export
  your OCI tenancy namespace before building, e.g.
  `export OCIR_NAMESPACE=mytenancynamespace`.

```bash
OCIR_NAMESPACE=mytenancynamespace ./mvnw -pl ingestion-service compile jib:build
```

CI currently only builds and tests (see `.github/workflows/ci.yml`) — it
does not push images yet.

## GitHub project board / labels

Not something a script in this repo can set up. Once you have the GitHub
CLI authenticated (`gh auth status`), from the repo root:

```bash
for p in phase-0 phase-1 phase-2 phase-3 phase-4 phase-5 phase-6 phase-7 phase-8 phase-9 phase-10; do
  gh label create "$p" --color BFD4F2
done
gh label create "type:feature" --color 0E8A16
gh label create "type:infra" --color 5319E7
gh label create "type:docs" --color 006B75

gh project create --owner @me --title "RAG Help Center"
```

(`gh project create` needs the `project` scope —
`gh auth refresh -s project` if you hit a permissions error.)
