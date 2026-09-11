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
Kafka: message.disposed        (published after retention/legal disposition deletes it)
        |
        v
search-service  :8082          message.ingested -> read from Mongo, index it
        |                       message.disposed -> delete the document
        v
Elasticsearch index "messages"  :9200
        |
        v
GET /api/search  :8082
```

Other services: **case-hold-service** :8083 (PostgreSQL, publishes
`case-hold.events`, which ingestion projects onto messages as `holdIds` /
`holdCount` / `dispositionStatus`) and **export-audit-service** :8084, which
owns presigned S3 download URLs.

Applying a hold writes those three fields straight to MongoDB, so it also sets
the message's `outboxStatus` back to `PENDING`. That makes `OutboxPublisher`
republish `message.ingested`, which is the only way search-service learns a
message changed. Skip it and the index reports every held message as
`holdCount: 0` forever, so `?onHold=true` and the `holdId` filter never match.
Convergence is bounded by `OUTBOX_INTERVAL_MS` (15s default).

A **gateway** on :8080 serves the UI and proxies `/api/*` to all four services.
It is the single browser origin, which is why no service configures CORS. Its
route table in `infrastructure/gateway/nginx.conf` must stay in step with
`discovery-hub-ui/proxy.conf.json`, which provides the same paths for
`ng serve`; if they diverge, a screen works in development and 404s in Docker.

Ingestion does **not** write to Elasticsearch. The search service owns its own
projection.

## Repository layout

| Path | Contents |
|------|----------|
| `ingestion-service/` | Ingestion API + async worker, retention/disposition (port 8081) |
| `search-service/` | Elasticsearch projection + search API (port 8082) |
| `case-hold-service/` | Cases and legal holds, PostgreSQL (port 8083) |
| `export-audit-service/` | Exports, audit trail, presigned S3 URLs (port 8084) |
| `discovery-hub-ui/` | Angular 22 front end, served by nginx behind the gateway |
| `corpus-generator/` | Python synthetic corpus generator |
| `infrastructure/` | Docker Compose stack, including the nginx gateway |
| `.env.example` | Configuration template |

There is **no parent/aggregator POM**. The two services are independent Maven
projects. In IntelliJ you must add each `pom.xml` separately via
*right-click -> Add as Maven Project*, or neither will be recognised.

## Build and test

Both services have a Maven wrapper. There may be no `mvn` on the PATH, so
always prefer `./mvnw`.

```bash
cd search-service    && ./mvnw test
cd ingestion-service && ./mvnw test
cd case-hold-service && ./mvnw test
cd export-audit-service && mvn test        # no wrapper in this module
cd discovery-hub-ui  && npm test && npm run build
```

**`export-audit-service` has no Maven wrapper.** Every other module does. Use
`mvn` there, or run it from another module's wrapper.

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
| **Web app (start here)** | `http://localhost:8080` |
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
`docker compose up -d --build search-service`. This is the first thing to check
when a screen contradicts the source — the served bundle is a build artefact,
and the UI container will happily serve last week's template against today's
API.

**`MONGODB_URI` and the S3 buckets may point at shared, remote infrastructure.**
A `.env` copied from a teammate can aim every service at a MongoDB Atlas
cluster and a real S3 bucket rather than at the local containers. When that is
the case, `stown-mongodb` and `stown-minio` keep running and stay nearly
empty, so `docker exec stown-mongodb mongosh` reports a handful of documents
while the APIs serve thousands. Check where a service actually points before
concluding data is missing:

```bash
docker exec stown-ingestion-service printenv MONGODB_URI S3_ENDPOINT S3_BUCKET
```

**Attachment objects live in whichever bucket was configured at ingestion
time,** and the bucket name is stored on the message. Point `S3_ENDPOINT` at
MinIO after a corpus was ingested against real S3 and every export fails with
`The specified bucket does not exist` (HTTP 404) naming a bucket that exists in
AWS but not in MinIO. `AWS_REGION` must also match the bucket, or S3 answers
`PermanentRedirect`. For a real bucket: empty `S3_ENDPOINT`, empty
`S3_PUBLIC_BASE_URL`, `S3_PATH_STYLE_ACCESS=false`, and
`S3_SOURCE_BUCKET` equal to the bucket the attachments were written to.

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

MongoDB is the source of truth. **Then rebuild it**, because reconciliation
only back-fills the newest batch:

```bash
curl -X POST "http://localhost:8082/api/search/reindex"
```

**Plain reindex will not repair a document that already exists.** It skips on
`exists(id)`, so a corpus whose *content* drifted (rather than being absent)
comes back `scanned:N indexed:0 skipped:N`. Rewriting every document needs the
`force` flag, which is what a mapping change requires:

```bash
curl -X POST "http://localhost:8082/api/search/reindex?force=true"
```

**Always confirm the mapping after a rebuild.** `MessageIndexClient` caches
"the index exists", and until that cache was invalidated on reindex, the delete
above left the flag set: creation was skipped and the first write made
Elasticsearch auto-create the index with a *dynamic* mapping. Every `keyword`
field became analysed `text`, so exact-match filters on `communicationType`,
`threadId`, `holdIds` and `dispositionStatus` silently returned nothing and the
`messageId` sort failed with `all shards failed`. `messageId` must read
`keyword`, not `text`:

```bash
curl -s localhost:9200/messages/_mapping | grep -o '"messageId":{"type":"[a-z]*"'
```

Nothing else in search re-indexes a changed document: the reconciliation
back-fill tests existence only, and the orphan sweep only deletes. Any state
that reaches a message after ingestion has to arrive as a fresh
`message.ingested` event — which is why the hold projection re-arms the outbox
(see below).

**A database that already holds messages will not index itself.** Anything with
`outboxStatus: PUBLISHED` has no further events coming, and the reconciliation
back-fill looks only at the newest `reconcile-batch-size` documents. Point
search at a shared cluster or a restored backup and you get the most recent
handful of messages and nothing else, silently. Run the reindex endpoint.

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

- **Back-fill only scans the newest `reconcile-batch-size` messages** by
  `createdAt`, so it cannot repair gaps in older data. This is deliberate — it
  is a cheap safety net for fresh gaps. To populate or rebuild an index, use
  `POST /api/search/reindex`, which walks the whole collection.
- **A shared database with separate Kafka brokers causes index drift.** If one
  environment deletes a message from a shared MongoDB but publishes
  `message.disposed` to a broker the search service does not consume, the
  document survives in Elasticsearch. The orphan sweep in `ReconciliationJob`
  cleans this up within a full pass of the index, but the underlying
  arrangement is worth avoiding.
- **No authentication, authorisation or tenant isolation** on any service.
  `POST /api/search/reindex` is unauthenticated and expensive, so it should not
  be exposed publicly as-is.
- **Contracts are duplicated, not shared.** `MessageDocument`,
  `AttachmentMetadata`, `MessageIngestedEvent`, `MessageDisposedEvent` and
  `AuditEvent` exist as independent copies per service, bound by field name
  over Kafka and MongoDB. A field renamed in `ingestion-service` produces **no
  compile error** anywhere else — the other side silently reads `null` and the
  failure surfaces later as missing data. Run
  `infrastructure/check-contract-drift.sh` whenever the model changes; it
  compares every copy against the ingestion one.
- **Two UI capabilities have no backend, and it is a modelling gap rather than
  a missing controller.** `environment.mockBacked` lists them: attaching a
  custodian to a case, and removing a single evidence item. The case service
  models *communications* on a case, never people, so there is nowhere to store
  a case-to-custodian relation. Both need a domain decision first.
- **Multiple hold participants are OR'd by the search filter**, matching what
  `LegalHoldProjectionService.criteriaFilter` does in ingestion. If one changes,
  the hold scope preview stops agreeing with the hold it previewed.

## Demonstrating retention and disposition

Use the **Retention demo** screen (`/demo`). It posts one message to the normal
ingestion endpoint with `retentionMinutes` between 1 and 5, so it travels
API → Kafka → worker → MongoDB → S3 like any other, then polls until the
document and its attachment objects are gone from both stores.

Per-message retention is the safe lever. Shortening a *type's* period — through
`RETENTION_EMAIL=1m` or the policy API — applies to every message of that type
already archived, so the next disposition run deletes the whole corpus. A
per-message period cannot affect anything ingested before it. It is bounded by
`RETENTION_MESSAGE_OVERRIDE_MAX` (5 minutes) and gated by
`RETENTION_MESSAGE_OVERRIDE_ENABLED`, which is deliberately *not* wired to
`ALLOW_SHORT_RETENTION`.

The disposition job must be running, and often enough to watch:

```bash
DISPOSITION_ENABLED=true
DISPOSITION_INTERVAL_MS=15000
PURGE_SWEEP_INTERVAL_MS=15000
```

Before enabling it against a populated archive, confirm nothing is already
past retention — the job deletes on its first pass:

```bash
# expect 0
docker run --rm --network host mongo:7 mongosh "$MONGODB_URI" --quiet \
  --eval 'db.messages.countDocuments({retentionUntil: {$lte: new Date()}})'
```

`GET /api/ingestion/messages/{id}/storage` reports whether the document and
each attachment object still exist, and keeps working after disposition by
reading the object keys from the `disposition_audit` record. Deleting the
document while orphaning the binaries in S3 is otherwise indistinguishable
from a complete purge.

## Verification scripts

```bash
cd infrastructure

# End-to-end across all five services, through the gateway: ingest -> search
# -> case -> hold -> blocked delete -> export -> verify -> audit. 41 checks.
./smoke-test.sh

# Field-level drift between the contracts each service copies. Exits 1 on an
# unexpected difference; deliberate omissions are listed with a reason in
# EXPECTED_ABSENT inside the script.
./check-contract-drift.sh
```

Run both before opening a PR. There is no CI, so nothing else will.

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
