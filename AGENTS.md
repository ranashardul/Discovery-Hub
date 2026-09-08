# AGENTS.md

Operational context for AI coding agents and new contributors working in this
repository. Read this before making changes.

## What this project is

Discovery Hub is a legal-discovery platform built as independently deployable
Spring Boot microservices. Communications are ingested asynchronously, stored
durably, and made searchable within 30 seconds.

```text
POST /api/ingestion/messages  :8081
        |
        v
Kafka: ingestion.requested
        |
        v
IngestionWorker  --> MongoDB legal_discovery.messages
                 --> S3 / MinIO (attachment binaries)
        |
        v
Kafka: message.ingested        (published from an outbox on the message document)
        |
        v
search-service  :8082          consumes the event, reads the message from Mongo,
        |                       indexes it into Elasticsearch
        v
Elasticsearch index "messages"  :9200
        |
        v
GET /api/search  :8082
```

Ingestion does **not** write to Elasticsearch. The search service owns its own
projection.

## Repository layout

| Path | Contents |
|------|----------|
| `ingestion-service/` | Ingestion API + async worker (port 8081) |
| `search-service/` | Elasticsearch projection + search API (port 8082) |
| `corpus-generator/` | Python synthetic corpus generator |
| `infrastructure/` | Docker Compose stack |
| `.env.example` | Configuration template |

There is **no parent/aggregator POM**. The two services are independent Maven
projects. In IntelliJ you must add each `pom.xml` separately via
*right-click -> Add as Maven Project*, or neither will be recognised.

## Build and test

Both services have a Maven wrapper. There may be no `mvn` on the PATH, so
always prefer `./mvnw`.

```bash
cd search-service    && ./mvnw test        # 33 unit tests, no containers
cd ingestion-service && ./mvnw test
```

Integration tests are tagged `integration` and excluded from the default
surefire run. Clearing `excluded.test.groups` is required, and Docker must be
running (Testcontainers starts MongoDB, Kafka and Elasticsearch):

```bash
cd search-service && ./mvnw test -Dgroups=integration -Dexcluded.test.groups=
```

## Running the stack locally

```bash
cp .env.example .env
ln -s ../.env infrastructure/.env
cd infrastructure && docker compose up -d --build
docker compose ps
```

| Service | URL |
|---------|-----|
| Ingestion API | `http://localhost:8081` |
| Search API | `http://localhost:8082` |
| Elasticsearch | `http://localhost:9200` (no auth; `xpack.security.enabled: false`) |
| Kafka UI | `http://localhost:8085` |
| MinIO console | `http://localhost:9001` |
| MongoDB | `mongodb://localhost:27017/legal_discovery` |

Kafka advertises `localhost:9092` for host clients and `kafka:19092` for
containers on the Compose network. Use container names, never `localhost`, from
inside a container.

## Gotchas that cost real debugging time

**`.env.example` ships placeholders that break the local stack.**
`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` default to `<your-minio-user>` style
placeholders. MinIO then boots with one placeholder as its root user while
ingestion authenticates with a different one, so every attachment upload fails
with `403 The Access Key Id you provided does not exist` surfaced as a bare
HTTP 500. For local runs set the MinIO variables and the matching
`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` to the same value
(`minioadmin` matches the Compose defaults). Leave the AWS variables as
placeholders when targeting real S3.

**The ingestion endpoint is `/api/ingestion/messages`.** Not `/api/messages`.
The wrong path returns a **500**, not a 404, so it looks like a service fault
rather than a typo.

**Containers do not rebuild when you switch branches or edit code.** If the API
behaves as though your change never happened, rebuild:
`docker compose up -d --build search-service`.

**Do not run a service from your IDE while its container is also running.** Both
bind the same port, and both join Kafka consumer group `search-service`, so
partitions are split between them and events arrive at one instance
unpredictably. `docker compose stop search-service` first.

**`text` vs `keyword` in Elasticsearch.** Exact-match (`term`) filters must
target a `keyword` field or its `.keyword` sub-field. A `term` query against an
analysed `text` field returns **zero hits with no error** — indistinguishable
from "nothing matched". This is why the code filters on `sender.keyword` and
why `dispositionStatus` is mapped as `keyword`.

**Adding a field to the Elasticsearch mapping is not retroactive.** An index
created before a new field was mapped has no mapping for it, so Elasticsearch
infers one on first write (`text` for strings) and exact-match filters on that
field silently return nothing. Fix per environment:

```bash
curl -X DELETE "http://localhost:9200/messages"
```

MongoDB is the source of truth; `ReconciliationJob` back-fills the index within
`SEARCH_RECONCILE_INTERVAL_MS` (default 60s).

## Conventions

- Java 21, Spring Boot 4, Maven.
- Packages are `com.stown.<service>` (`com.stown.ingestion`, `com.stown.search`).
- Layering is `api/ service/ repository/ domain/ config/ index/ messaging/`.
  Request/response DTOs live in `api/`, not a separate `dto/` package.
- Lombok is used throughout (`@Data`, `@Builder`, `@Slf4j`,
  `@RequiredArgsConstructor`). IntelliJ needs the Lombok plugin **and**
  *Enable annotation processing*, or you will see hundreds of phantom
  "cannot resolve method" errors.
- Configuration comes from environment variables with sensible local defaults.
  Never hardcode connection strings, credentials or secrets.
- Error responses use a shared shape: `timestamp`, `status`, `error`,
  `message`, `path`, `fieldErrors[]`. Validation failures name the offending
  field.

## Git workflow

- **`main` is protected.** Nobody can push to it directly. All work goes on a
  branch, then a pull request.
- Always branch from fresh `main`:
  `git checkout main && git pull && git checkout -b <topic>`.
- Commit messages follow `type(scope): subject` with a body explaining **why**,
  not what. See `git log` for examples.
- **Do not add "Generated with Devin" or `Co-Authored-By: Devin` trailers to
  commit messages.**
- There is **no CI**. Branch protection enforces "merge via PR" but cannot
  enforce "merge via *passing* PR", so run the test suites locally before
  opening one.

## Security

The repository is **public**. `.env` is gitignored and has never been tracked.
Before pushing, verify no `.env` file, AWS key, or password has been added to
tracked files — including `docker-compose.yml`, `application.yaml` and
Markdown. A credential pushed to a public repository must be **rotated**;
reverting the commit is not sufficient.

## Known open items

- **The search service has no delete path.** `MessageIndexClient` has no
  `delete` operation, so a message removed from MongoDB stays searchable in
  Elasticsearch indefinitely. This matters once archival/disposition starts
  removing or tombstoning messages.
- **The indexing failure ledger retries forever.** `ReconciliationJob` retries
  every unresolved `search_index_failures` entry with no attempt cap and no
  give-up path, so a message that no longer exists in MongoDB can never
  resolve and `pendingFailures` grows without bound.
- **Back-fill only scans the newest `reconcile-batch-size` messages** by
  `createdAt`, so it cannot repair gaps in older data.
- **No authentication, authorisation or tenant isolation** on either service.
- **`holdCount` and `dispositionStatus` are inert.** Ingestion hardcodes
  `holdCount(0)` and `dispositionStatus("ACTIVE")`; no service updates them
  yet. The `onHold` search filter is implemented and tested but will always
  return zero results for `onHold=true` until a case/hold service exists.
- **Contracts are duplicated, not shared.** `MessageDocument`,
  `AttachmentMetadata` and `MessageIngestedEvent` exist as independent copies
  in both services. A field renamed in `ingestion-service` produces **no
  compile error** in `search-service` — it silently reads `null`. Diff these
  classes by hand whenever the ingestion model changes.

## Useful checks

```bash
# how many messages are searchable
curl -s localhost:8082/api/search/stats

# does Elasticsearch agree with MongoDB
curl -s localhost:9200/messages/_count
docker exec stown-mongodb mongosh --quiet legal_discovery \
  --eval 'db.messages.countDocuments()'

# verify the mapping (dispositionStatus must be keyword, not text)
curl -s localhost:9200/messages/_mapping

# anything stuck in the dead-letter topic (should be empty)
# Kafka UI -> Topics -> message.ingested.dlt
```
