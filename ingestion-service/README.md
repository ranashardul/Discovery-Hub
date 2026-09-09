# Ingestion Service — Architecture and Code Guide

This document explains how the service is built: what it does, how the
pieces fit together, what every file is for, why each technology is used,
and the things that will catch you out.

For commands to actually run it, see `ingestion-service-run.md`.

Related documents:

-   `ingestion-service-run.md` — **run book: every command, start to finish**
-   `../docs/retention-and-holds.md` — retention, disposition and legal holds
-   `../docs/s3-archival.md` — attachment archival and retrieval
-   `../AGENTS.md` — repository conventions and cross-service gotchas
-   `../README.md` — the platform as a whole

## 1. What This Service Does

The Ingestion Service is the entry point for communication records
(emails and chats) entering Discovery Hub. It has two halves that run
inside the same deployable but never share responsibilities:

  Half     Role
  -------- ----------------------------------------------------------
  API      Accept, validate, stage attachments, register, publish
  Worker   Store durably in MongoDB and S3, then publish the event

The split exists because ingestion must be asynchronous. A client such
as the corpus generator can submit thousands of messages per second
without waiting for database writes or object uploads.

On top of that it owns the **lifecycle** of every message it stores:
retention is resolved and recorded at ingestion, legal holds published by
the Case & Hold service are projected onto the stored documents, and a
scheduled process deletes what is past retention and not on hold.

The service owns two MongoDB collections of record (`messages`,
`ingestion_requests`), the attachment objects in S3, and three supporting
collections for holds and disposition. No other service writes to them.

### Responsibilities at a glance

  Area | This service | Somebody else
  -----|--------------|--------------
  Accepting and validating messages | yes | —
  Immutable message identity | yes | —
  Attachment binaries in S3 | yes | —
  Deduplication and idempotency | yes | —
  Retention and disposition | yes | —
  Refusing deletion of held messages | yes | —
  **Placing and releasing holds** | no, consumed as events | Case & Hold service
  **Search index** | no, publishes events only | Search service
  **Chain-of-custody audit** | records its own decisions | Export & Audit service

## 2. Architecture

``` text
                 HTTP POST /api/ingestion/messages
                                |
                                v
  +---------------------------------------------------------------+
  |  API half                                                     |
  |                                                               |
  |  IngestionController                                          |
  |        |                                                      |
  |        v                                                      |
  |  IngestionRequestService                                      |
  |        |-- HashService ............ SHA-256 dedup key         |
  |        |-- S3StorageService ....... upload to staging/        |
  |        |-- IngestionRequestRepository ... register request    |
  |        +-- KafkaTemplate .......... publish request event     |
  +---------------------------------------------------------------+
                                |
                     202 Accepted returned here
                                |
                                v
                   Kafka topic: ingestion.requested
                                |
                                v
  +---------------------------------------------------------------+
  |  Worker half                                                  |
  |                                                               |
  |  IngestionWorker (@KafkaListener)                             |
  |        |-- assign immutable messageId                         |
  |        |-- AttachmentStorageService ... copy to messages/     |
  |        |-- MessageRepository .......... insert document       |
  |        +-- OutboxPublisher ........... publish + mark sent    |
  +---------------------------------------------------------------+
                       |                    |
                       v                    v
                   MongoDB                 S3
              messages,               attachment
              ingestion_requests      binaries
                       |
                       v
                Kafka topic: message.ingested
                       |
                       v
                 Search Service -> Elasticsearch
```

Retention runs alongside that flow, on its own schedule:

``` text
  case-hold service                        this service
        |                                       |
        +--- case-hold.events ---------------->  LegalHoldProjectionService
             HOLD_CREATED / HOLD_RELEASED        |  holdIds set per message
                                                 |  holdCount + status derived
                                                 v
                                        DispositionService (@Scheduled)
                                                 |
                              retentionUntil <= now AND holdCount = 0
                                                 |
                     +---------------------------+---------------------+
                     v                           v                     v
              MongoDB delete                S3 objects           Kafka topic:
              (conditional)                   purged            message.disposed
                                                 |
                                                 v
                                    disposition_runs + disposition_audit
```

### Request lifecycle

1.  **Validate.** Bean Validation rejects malformed requests with a
    structured `400`.
2.  **Fingerprint.** `HashService` builds a canonical string and hashes
    it with SHA-256. This is the deduplication key.
3.  **Short-circuit duplicates.** If a request with that key (or the
    same `externalMessageId`) already exists, the original is returned
    and nothing else happens.
4.  **Stage binaries.** Attachments are decoded and uploaded to
    `staging/{requestId}/{index}-{filename}`.
5.  **Register.** An `ingestion_requests` document is inserted with
    status `RECEIVED`. Its unique indexes make step 3 safe under
    concurrency.
6.  **Publish.** `ingestion.requested` is published carrying only object
    references, never binaries.
7.  **Respond.** HTTP `202 Accepted`.
8.  **Consume.** The worker loads the request, skips it if already
    `INGESTED`, and marks it `PROCESSING`.
9.  **Assign identity.** A `messageId` is generated once and stored on
    the request, so retries reuse it.
10. **Materialize attachments.** Staged objects are copied server-side
    to `messages/{messageId}/attachments/{attachmentId}/{filename}`.
11. **Store.** The message document is inserted with an outbox marker of
    `PENDING`.
12. **Publish and confirm.** `message.ingested` is sent; on success the
    marker flips to `PUBLISHED` and staged objects are deleted.

### Retention lifecycle

1.  **Resolve.** At ingestion, `RetentionPolicy` looks up the period for the
    communication type and stores the absolute `retentionUntil`.
2.  **Project holds.** `case-hold.events` adds or removes a `holdId` on the
    matching messages; `holdCount` and `dispositionStatus` are derived in the
    same atomic update.
3.  **Scan.** The scheduled job loads messages whose `retentionUntil` has
    passed.
4.  **Skip held.** `holdCount > 0` is recorded as `SKIPPED_ON_HOLD` and left
    alone.
5.  **Record intent.** An audit record is written with the object keys.
6.  **Delete conditionally.** `findAndRemove` filtered on `holdCount = 0`.
7.  **Purge and publish.** S3 objects are deleted, `message.disposed` is
    published, and the audit record is closed.

### Five design decisions worth knowing

**Binaries never travel through Kafka.** Events would otherwise blow
past broker message limits. The API stages the binary in S3 first and
the event carries `stagingBucket` / `stagingKey`. The worker still owns
the durable object, so the API never writes final state.

**The message ID is assigned once.** It is the MongoDB `_id`, the
Elasticsearch document ID, and part of every S3 object key. It is stored
on the request document before any storage work, so a retried or
dead-lettered event reuses the same identity instead of creating an
orphan.

**The outbox marker lives on the message document.** A separate outbox
collection would need a multi-document transaction, which requires a
replica set; a standalone MongoDB container cannot do that. Keeping the
marker inside the message makes the write atomic everywhere, at the cost
of at-least-once delivery. Consumers key on `messageId` and must be
idempotent.

**Holds are a set, not a counter.** Each message carries `holdIds` and derives
`holdCount` from it. Hold events are delivered at least once, so incrementing
would double-count on redelivery, and a release event names only the hold, not
the messages it covered. Overlapping holds then work by construction:
releasing one hold clears protection only when no other hold id remains.

**Retention is never enforced with a TTL index.** MongoDB TTL deletion has no
conditional predicate, so it would delete held messages, orphan the S3
objects, bypass the audit trail and leave the content searchable. The job
instead makes the hold check and the delete a single conditional
`findAndRemove`, and writes the audit record first so a crash mid-purge cannot
strand the binaries.

## 3. Technology Used and Why

  Technology                    Why it is used
  ----------------------------- ------------------------------------------------
  Java 21                       Records, pattern matching, virtual-thread-ready
  Spring Boot 4.1               Auto-configuration, actuator, config binding
  Spring Web MVC                REST controllers (`spring-boot-starter-webmvc`)
  Spring Validation             Declarative request validation with Jakarta
  Spring Data MongoDB           Repositories, document mapping, index creation
  Spring Kafka                  Producer, `@KafkaListener`, retry, dead letters
  AWS SDK v2 (S3)               Object storage against AWS S3 or MinIO
  Lombok                        Removes boilerplate on documents and events
  Maven                         Build, dependency management via the Boot BOM
  MongoDB                       Source of truth for message metadata
  Apache Kafka (KRaft)          Asynchronous decoupling between the halves
  MinIO                         S3-compatible object store for local runs
  Elasticsearch                 Search projection, owned by the search service
  Docker Compose                One-command local stack
  Testcontainers                Integration tests against real infrastructure

### Boot 4 specifics that are easy to get wrong

-   The MongoDB URI belongs under `spring.mongodb.uri`. Putting it under
    `spring.data.mongodb.uri` is silently ignored and the driver falls
    back to `mongodb://localhost/test`. Only Spring Data options such as
    `auto-index-creation` live under `spring.data.mongodb`.
-   Boot 4 ships Jackson 3. Kafka JSON must use `JacksonJsonSerializer`
    and `JacksonJsonDeserializer`. The older `JsonSerializer` uses a
    Jackson 2 mapper without JSR-310 support and fails on `Instant`.
-   Starters are module specific: `spring-boot-starter-webmvc`, not
    `spring-boot-starter-web`.

## 4. API Reference

  Method and path | Purpose
  ----------------|--------
  `POST /api/ingestion/messages` | Accept a message. Returns `202` with the request id and deduplication key.
  `GET /api/ingestion/requests/{requestId}` | Status of one request.
  `GET /api/ingestion/requests?externalMessageId=` | Same, looked up by source id or `deduplicationKey`. This is how you find the assigned `messageId`.
  `GET /api/ingestion/messages/{messageId}/retention` | Retention countdown and hold state.
  `DELETE /api/ingestion/messages/{messageId}` | Delete on request. **`409` when a legal hold covers it.**
  `GET /api/ingestion/disposition/runs?limit=` | Recent disposition runs: scanned, deleted, skipped on hold.
  `GET /actuator/health` | Liveness and readiness.

There is deliberately **no** endpoint for placing or releasing a hold; that
belongs to the Case & Hold service, and holds arrive here only as events.

Copy-paste examples for all of these are in `ingestion-service-run.md`.

### Ingest a message

``` http
POST /api/ingestion/messages
Content-Type: application/json
```

``` json
{
  "communicationType": "EMAIL",
  "sender": "alice.sharma@example-bank.test",
  "recipients": ["bob.patel@example-bank.test"],
  "subject": "Project Atlas: budget review",
  "body": "Please review the latest budget information.",
  "messageTimestamp": "2026-09-08T03:00:00Z",
  "threadId": "thread-00001",
  "externalMessageId": "corpus-000001",
  "attachments": [
    {
      "filename": "budget-review.pdf",
      "contentType": "application/pdf",
      "contentBase64": "JVBERi0xLjQK..."
    }
  ]
}
```

Response, HTTP `202 Accepted`:

``` json
{
  "requestId": "generated-request-uuid",
  "deduplicationKey": "generated-sha256-key",
  "status": "RECEIVED",
  "duplicate": false,
  "messageId": null
}
```

`messageId` is `null` until the worker assigns it. `duplicate` is `true` when
the message was already submitted; the response then carries the original
`requestId`, `status` and `messageId`, and no new document, object or event is
created.

#### Request fields

  Field | Type | Required | Notes
  ------|------|----------|------
  `communicationType` | string | yes | `EMAIL` or `CHAT`. Any other value is accepted but falls back to the default retention period.
  `sender` | string | yes | Must be a well-formed email address.
  `recipients` | array of strings | yes | Non-empty; each must be a well-formed email address.
  `subject` | string | yes | Must not be blank.
  `body` | string | yes | Must not be blank.
  `messageTimestamp` | ISO-8601 instant | yes | When the communication originally happened.
  `threadId` | string | no | Conversation identifier.
  `externalMessageId` | string | no | Source-system identifier. Participates in deduplication.
  `attachments` | array of objects | no | See below.

Each attachment object:

  Field | Type | Required | Notes
  ------|------|----------|------
  `filename` | string | yes | Original file name.
  `contentType` | string | no | MIME type.
  `contentBase64` | string | yes | Base64-encoded binary.

Attachment binaries are uploaded to S3 by the API before the event is
published, so Kafka events stay small and never carry binary content.

### Look up request status

``` http
GET /api/ingestion/requests/{requestId}
GET /api/ingestion/requests?deduplicationKey={key}
GET /api/ingestion/requests?externalMessageId={id}
```

``` json
{
  "requestId": "generated-request-uuid",
  "deduplicationKey": "generated-sha256-key",
  "externalMessageId": "corpus-000001",
  "messageId": "generated-message-uuid",
  "status": "INGESTED",
  "attempts": 1,
  "lastError": null,
  "attachmentCount": 1,
  "createdAt": "2026-09-08T03:42:04.702Z",
  "updatedAt": "2026-09-08T03:42:04.720Z"
}
```

Statuses are `RECEIVED`, `PROCESSING`, `INGESTED`, `FAILED` and `DISPOSED`. A
`FAILED` request keeps its `messageId`, so a retry reuses the same identity.
`DISPOSED` means the stored message was deleted after its retention expired,
so a re-submission reports that rather than deduplicating against a message
that no longer exists. Unknown identifiers return `404`.

### Retention and hold state

``` http
GET /api/ingestion/messages/{messageId}/retention
```

``` json
{
  "messageId": "generated-message-uuid",
  "communicationType": "EMAIL",
  "retentionUntil": "2026-09-08T10:01:00Z",
  "secondsUntilExpiry": 42,
  "retentionExpired": false,
  "held": true,
  "holdCount": 1,
  "holdIds": ["hold-7c1a"],
  "dispositionStatus": "ON_HOLD"
}
```

### Delete a message

``` http
DELETE /api/ingestion/messages/{messageId}
```

Deletes the message, purges its attachment objects and publishes
`message.disposed`. A message under legal hold is refused with `409`:

``` json
{
  "status": 409,
  "error": "Conflict",
  "message": "Message ... is under legal hold (holdCount=1); deletion refused; holds: hold-7c1a"
}
```

### Disposition runs

``` http
GET /api/ingestion/disposition/runs?limit=10
```

Recent runs with `scanned`, `deleted`, `skippedOnHold`, `failed`,
`s3ObjectsPurged` and `dryRun`.

### Error shape

Every error uses the same body. Validation failures name the offending
fields:

``` json
{
  "timestamp": "2026-09-07T20:28:09.666388679Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/ingestion/messages",
  "fieldErrors": [
    { "field": "sender", "message": "must be a well-formed email address" },
    { "field": "recipients", "message": "must not be empty" }
  ]
}
```

  Status | When
  -------|-----
  `400` | Validation failure, malformed JSON, or undecodable attachment base64.
  `404` | Unknown request id or message id.
  `409` | Deletion refused because a legal hold covers the message.
  `503` | The object store rejected an attachment upload; the request is retryable.
  `500` | Anything unexpected.

### Deduplication

A SHA-256 key is computed **at ingestion** from a canonical form of:
communication type, sender, sorted recipients, subject, body, message
timestamp, thread id and external message id. Clients never send the key.

`externalMessageId` participates on purpose: a source-system identifier
distinguishes two genuinely different messages that happen to carry identical
content, while re-submitting the same source message produces the same key and
stays idempotent.

The key is checked against `ingestion_requests` by the API, then against
`messages` again by the worker, and unique indexes settle any race — the
losing writer gets a duplicate-key error and resolves to the winning
document. A duplicate creates no second document, no second object and no
second logical event.

Indexes are created from the `@Indexed(unique = true)` mappings because
`spring.data.mongodb.auto-index-creation` is enabled. The manual equivalents:

``` javascript
db.messages.createIndex({ deduplicationKey: 1 },
  { unique: true, name: "deduplicationKey_unique" })
db.messages.createIndex({ externalMessageId: 1 },
  { unique: true, sparse: true, name: "externalMessageId_unique" })
db.ingestion_requests.createIndex({ deduplicationKey: 1 },
  { unique: true, name: "request_deduplicationKey_unique" })
db.ingestion_requests.createIndex({ externalMessageId: 1 },
  { unique: true, sparse: true, name: "request_externalMessageId_unique" })
```

## 5. Folder Structure

``` text
ingestion-service/
├── Dockerfile                        multi-stage build, non-root Alpine JRE
├── pom.xml                           dependencies; Boot 4.1, Java 21
├── mvnw, mvnw.cmd                    Maven wrapper (no global Maven needed)
├── README.md                         this file: architecture, API, code guide
├── ingestion-service-run.md          run book: every command
└── src/
    ├── main/
    │   ├── java/com/stown/ingestion/
    │   │   ├── IngestionApplication.java      entry point, scheduling on
    │   │   ├── api/            HTTP layer: controllers, request/response DTOs,
    │   │   │                   error shape and the exception handler
    │   │   ├── config/         Kafka topics and factories, S3 client,
    │   │   │                   configuration properties, startup checks
    │   │   ├── domain/         persisted documents and their enums
    │   │   ├── messaging/      event contracts in and out, plus the
    │   │   │                   case-hold listener
    │   │   ├── repository/     Spring Data Mongo repositories
    │   │   ├── service/        business logic: dedup, storage, retention,
    │   │   │                   disposition, hold projection, outbox
    │   │   └── worker/         the Kafka consumer that stores messages
    │   └── resources/
    │       └── application.yaml       all configuration, env-var driven
    └── test/java/com/stown/ingestion/
        ├── AbstractIntegrationTest.java   Testcontainers base
        ├── api/, domain/, messaging/, service/   mirrors the main packages
        └── ...
```

Layering is one-directional: `api → service → repository → domain`, with
`worker` and `messaging` sitting beside `service`. Request and response DTOs
live in `api/` rather than a separate `dto/` package, matching the other
services in the repository.

## 6. File Guide

### Application entry point

  File                       Purpose
  -------------------------- --------------------------------------------------
  `IngestionApplication`     Boot entry point. `@EnableScheduling` activates three background jobs: the outbox sweep, the disposition scan and the purge sweeper. `@EnableConfigurationProperties` binds the retention settings.

### `api` — HTTP layer

  File                                Purpose
  ----------------------------------- -------------------------------------------------
  `IngestionController`               `POST /api/ingestion/messages` returning `202`, plus the request status lookups.
  `IngestionRequest`                  Inbound payload with validation annotations.
  `AttachmentRequest`                 One inbound attachment: filename, content type, base64 content.
  `IngestionResponse`                 Acceptance response: requestId, dedup key, status, duplicate flag, messageId.
  `IngestionRequestStatusResponse`    Status projection of a request document, built by `from(...)`.
  `ApiErrorResponse`                  Error body: timestamp, status, error, message, path, field errors.
  `ApiExceptionHandler`               `@RestControllerAdvice` mapping validation, bad bodies, not-found, invalid attachments and unexpected errors to `ApiErrorResponse`.
  `IngestionRequestNotFoundException` Signals a `404` for an unknown request identifier.
  `MessageLifecycleController`        Retention status, the delete endpoint that refuses held messages, and recent disposition runs.
  `RetentionStatusResponse`           Retention countdown and hold state for one message.
  `DispositionRunResponse`            One run: scanned, deleted, skipped on hold, objects purged.

### `domain` — persisted model

  File                        Purpose
  --------------------------- --------------------------------------------------
  `MessageDocument`           The `messages` collection. Immutable `_id`, unique dedup key, sparse unique external ID, attachments, retention fields and outbox state.
  `AttachmentMetadata`        Embedded attachment record: id, filename, content type, size, SHA-256, bucket, key, URL.
  `IngestionRequestDocument`  The `ingestion_requests` collection. Idempotency guard and status registry; holds the assigned `messageId` and staged attachments.
  `StagedAttachment`          Reference to a binary sitting in the staging prefix. Travels inside the Kafka event.
  `IngestionStatus`           `RECEIVED`, `PROCESSING`, `INGESTED`, `FAILED`, `DISPOSED`.
  `OutboxStatus`              `PENDING`, `PUBLISHED`.
  `DispositionStatus`         String constants `ACTIVE` and `ON_HOLD`, kept as strings because search-service indexes the field as a keyword.
  `DispositionAudit`          The `disposition_audit` collection. One append-only record per disposition decision, written before the delete and carrying the object keys.
  `DispositionRun`            The `disposition_runs` collection. Per-run counts required by FR-5.3.
  `DispositionOutcome`        `PENDING`, `DELETED`, `SKIPPED_ON_HOLD`, `FAILED`.
  `HoldState`                 The `hold_state` collection. Last known status of each hold plus the event ids already applied.

### `repository` — data access

  File                           Purpose
  ------------------------------ -----------------------------------------------
  `MessageRepository`            Lookups by dedup key and external ID, plus outbox queries used by the publisher.
  `IngestionRequestRepository`   Lookups by dedup key and external ID for idempotency and status.
  `DispositionAuditRepository`   Per-message decisions, plus the query the purge sweeper uses to find incomplete work.
  `DispositionRunRepository`     Recent runs, newest first.
  `HoldStateRepository`          Hold status keyed by `holdId`.

### `messaging` — event contracts

  File                       Purpose
  -------------------------- --------------------------------------------------
  `IngestionRequestedEvent`  Accepted request: content plus staged attachment references. Consumed by the worker.
  `MessageIngestedEvent`     Durable-storage notification: eventId, messageId, dedup key, occurredAt. Consumed by the search service.
  `MessageDisposedEvent`     Published after a message and its objects are deleted, so the search index and the audit trail can follow.
  `CaseHoldEvent`            Permissive local view of an event on `case-hold.events`, owned by the case-hold service. Deliberately a copy, not a shared class.
  `CaseHoldEventListener`    Consumes that topic on a dedicated String-deserializer factory and routes on `eventType`.

### `service` — business logic

  File                        Purpose
  --------------------------- --------------------------------------------------
  `IngestionRequestService`   API-side orchestration: dedup, duplicate short-circuit, attachment staging, request registration, event publication. Rolls back staged objects if registration loses a race.
  `HashService`               Builds the canonical string and the SHA-256 dedup key.
  `S3StorageService`          All object-store access: key layout, upload, server-side copy, head, delete, URL construction, checksums, bucket bootstrap at startup.
  `AttachmentStorageService`  Turns staged attachments into durable objects and `AttachmentMetadata`; discards staged copies.
  `OutboxPublisher`           Publishes `message.ingested`, flips the outbox marker, and re-publishes anything left `PENDING` on a schedule.
  `InvalidAttachmentException` Raised for undecodable base64, surfaced as `400`.
  `RetentionPolicy`           Resolves the retention period per communication type and validates the configured policy at startup.
  `DispositionService`        The scheduled scan: skips held messages, deletes conditionally, purges objects, publishes `message.disposed`, records the run. Also serves the delete API.
  `PurgeSweeper`              Finishes disposition records whose object purge or event publication did not complete.
  `LegalHoldProjectionService` Applies `HOLD_CREATED` and `HOLD_RELEASED` to our messages as an atomic set update.
  `HeldMessageDeletionException` Raised when deletion is attempted on a held message, surfaced as `409`.
  `MessageNotFoundException`  Surfaced as `404` from the lifecycle endpoints.

### `worker` — storage half

  File                Purpose
  ------------------- --------------------------------------------------
  `IngestionWorker`   `@KafkaListener` on `ingestion.requested`. Skips completed requests, assigns the immutable message ID, materializes attachments, inserts the message, marks the request `INGESTED`, and hands off to the outbox. Rethrows failures so retry and dead-lettering apply.

### `config` — wiring

  File                  Purpose
  --------------------- --------------------------------------------------
  `KafkaConfig`         Topic definitions (including dead letters), the error handler with exponential backoff plus `DeadLetterPublishingRecoverer`, and the dedicated container factory for `case-hold.events`.
  `S3Config`            Builds the `S3Client` with optional endpoint override, path-style access and the default AWS credential chain.
  `StorageProperties`   Binds `app.storage.*`: bucket, region, endpoint, prefixes, public base URL.
  `MongoStartupCheck`   Logs the effective database, collections and indexes at startup, and warns when a unique index is missing.
  `RetentionProperties` Binds `app.retention.*`: per-type periods, the floor, the dry-run flag and the batch and delete caps.

### Resources and build files

  File                 Purpose
  -------------------- --------------------------------------------------
  `application.yaml`   All configuration, every connection value read from an environment variable with a local default.
  `pom.xml`            Dependencies and the Lombok annotation processor.
  `Dockerfile`         Multi-stage build: Maven builder, then a non-root Alpine JRE runtime with `curl` for health checks.
  `.dockerignore`      Keeps `target/`, git and IDE files out of the build context.

### Tests

  File                              Purpose
  --------------------------------- --------------------------------------------
  `AbstractIntegrationTest`         Boots the app against singleton MongoDB, Kafka and MinIO containers, wiring them in through `@DynamicPropertySource`.
  `IngestionApiIntegrationTest`     12 tests: async acceptance, worker storage, attachment upload and key layout, event payload size, idempotency, message-ID stability, status lookups, unique indexes, validation errors, CHAT support.
  `HashServiceTest`                 4 unit tests: key stability, recipient-order independence, content sensitivity, missing thread ID.
  `RetentionPolicyTest`             8 unit tests: per-type resolution, default fallback, expiry arithmetic, and the floor that refuses a demo period.
  `CaseHoldEventTest`               5 unit tests pinning the payload published by the case-hold service, including tolerance of fields we do not consume.
  `MessageContractTest`             Guards the field names and types search-service reads from our collection, so a rename fails our build instead of silently feeding it nulls.
  `RetentionDispositionIntegrationTest` 12 tests: delete on expiry, hold blocks disposition, overlapping holds, redelivered hold events, CRITERIA scope, 409 on held delete, the hold race, dry run, and the run record.
  `IngestionApplicationTests`       Context startup.

Integration tests use the singleton container pattern deliberately.
Static `@Container` fields restart per test class while the Spring
context is cached, which leaves the producer pointing at a dead broker.

## 7. Data Stored

### `messages`

Durable message documents. Indexes: `_id`, unique `deduplicationKey`,
sparse unique `externalMessageId`, and helper indexes on
`communicationType`, `sender`, `messageTimestamp`, `threadId`,
`outboxStatus`, `retentionUntil` and `holdIds`.

`holdCount` and `dispositionStatus` are also read directly by
search-service, which keeps its own copy of this model. Their names and
types are pinned by `MessageContractTest`.

### `ingestion_requests`

One document per accepted request. Indexes: `_id` (the request ID),
unique `deduplicationKey`, sparse unique `externalMessageId`. This is
where you look when a submission seems missing. A request whose message
has been disposed is left in place with status `DISPOSED`.

### `disposition_audit`

One append-only record per disposition decision: the outcome, the reason,
the hold state at the time, and the object keys. Written before the
message is deleted, which is what makes an interrupted purge recoverable.

### `disposition_runs`

One record per run with `scanned`, `deleted`, `skippedOnHold`, `failed`
and `s3ObjectsPurged`, satisfying FR-5.3.

### `hold_state`

Last known status of each legal hold and the event ids already applied,
so redelivery is observable and a stale create after a release is ignored.

### Object store

``` text
staging/{requestId}/{index}-{filename}                              transient
messages/{messageId}/attachments/{attachmentId}/{filename}          durable
```

## 8. Failure Behaviour

  Failure                      What happens
  ---------------------------- --------------------------------------------------
  Invalid request              `400` with field-level errors; nothing is written.
  Undecodable attachment       `400`; staged objects from the same request are removed.
  Concurrent duplicate         Unique index rejects the loser, which resolves to the winning document.
  Kafka down at accept time    The request is registered and the failure logged; the worker picks it up when the event is replayed.
  Worker failure               Request marked `FAILED` with the error, retried with backoff, then routed to `ingestion.requested.dlt`. The message ID is preserved.
  Kafka down after storage     Outbox marker stays `PENDING`; the scheduled sweep republishes.
  S3 unavailable               Upload or copy fails, the request goes `FAILED`, and the event is retried.
  Hold arrives mid-disposition The conditional delete matches nothing, so the message survives and is recorded `SKIPPED_ON_HOLD`.
  Delete attempted on a held message `409 Conflict` naming the blocking holds; nothing is deleted.
  Crash between delete and purge The audit record already holds the object keys, so `PurgeSweeper` completes the purge and publishes the event.
  Kafka down at disposition    The message is gone and the audit record keeps `eventPublished: false`; the sweeper republishes so the search index is not left holding deleted content.
  Hold event redelivered       The `holdIds` set makes it a no-op, so `holdCount` cannot drift.
  Retention period below the floor The service refuses to start unless short retention is explicitly allowed.

Operational queries:

``` javascript
db.ingestion_requests.find({ status: "FAILED" }, { lastError: 1, attempts: 1 })
db.messages.countDocuments({ outboxStatus: "PENDING" })

// Disposition work that did not finish
db.disposition_audit.countDocuments({ outcome: "PENDING" })
db.disposition_audit.find({ outcome: "DELETED", objectsPurged: false })

// Held messages, and what the last run did
db.messages.countDocuments({ holdCount: { $gt: 0 } })
db.disposition_runs.find().sort({ startedAt: -1 }).limit(1)
```

Manual commands and the full demo are in `../docs/retention-and-holds.md`.

## 9. Background Jobs

Three scheduled jobs run inside the same process. All three are safe to run
concurrently with request traffic, and all are bounded by a batch size.

  Job | Interval | What it does
  ----|----------|-------------
  `OutboxPublisher.republishPending` | `OUTBOX_INTERVAL_MS`, 15s | Re-sends `message.ingested` for anything still marked `PENDING`, so a broker outage cannot lose an event.
  `DispositionService.runScheduledDisposition` | `DISPOSITION_INTERVAL_MS`, 60s | Deletes messages past retention that are not on hold. **No-op unless `DISPOSITION_ENABLED=true`.**
  `PurgeSweeper.sweep` | `PURGE_SWEEP_INTERVAL_MS`, 60s | Finishes disposition records whose S3 purge or event publication did not complete.

## 10. Configuration

Every value is read from an environment variable with a local default, so the
same image runs locally and against Atlas and AWS. `application.yaml` is the
authoritative list; `../.env.example` documents each variable.

The groups are: `SERVER_PORT`, `MONGODB_URI`, `KAFKA_*`, `S3_*` and `AWS_*`,
`APP_RETENTION_*` (exposed as `RETENTION_*` and `DISPOSITION_*`),
`CASE_HOLD_TOPIC`, and `LOG_LEVEL`.

Three that cause the most trouble:

-   `MONGODB_URI` — a password with special characters must be
    percent-encoded, or the driver fails to parse the URI.
-   `S3_ENDPOINT` — **empty means real AWS S3**. Compose uses
    `${S3_ENDPOINT-http://minio:9000}` with a single dash for exactly this
    reason: `:-` would substitute the MinIO default over an intentionally
    empty value and quietly keep writing locally.
-   `AWS_REGION` — must match the bucket, or S3 answers
    `PermanentRedirect`. A bucket ARN carries no region, so it has to be
    configured separately.

## 11. Things Worth Knowing Before You Change Anything

**The endpoint is `/api/ingestion/messages`.** Not `/api/messages`. A wrong
path can surface as `500` rather than `404`, which looks like a service
fault.

**`202` does not mean stored.** The response carries no `messageId`; the
worker assigns it moments later. Poll
`GET /api/ingestion/requests?externalMessageId=...` until the status is
`INGESTED`. Anything asserting on storage immediately after the POST will be
flaky.

**Deduplication includes `externalMessageId`.** The canonical hash is
communication type, sender, sorted recipients, subject, body, timestamp,
thread id **and** external id. Two genuinely different messages that share
content stay distinct, and re-submitting the same source message is
idempotent. Clients never send the key; it is computed here and checked in
`ingestion_requests`, then again in `messages` by the worker.

**Other services read our documents directly.** search-service keeps its own
copy of `MessageDocument` with no compile-time link, so renaming
`holdCount` or `dispositionStatus` produces **no compile error** there — it
silently reads `null`. `MessageContractTest` fails our build if that
happens. Add fields freely; never rename or retype the consumed ones.

**A disposed message stays searchable.** Elasticsearch holds the subject and
body until search-service consumes `message.disposed`, and it has no delete
path yet. MongoDB and S3 no longer have the data, which makes the gap easy to
miss.

**Never enforce retention with a TTL index.** Explained in section 2; it
would delete held evidence with no audit trail.

**Do not run the service in an IDE while its container is running.** Both
bind port 8081 and both join the same Kafka consumer group, so partitions are
split and events arrive at one instance unpredictably.

**Rebuild after code changes.** `docker compose up -d` reuses the existing
image; add `--build`.

**Boot 4 details** that produce silent misbehaviour rather than errors are in
section 3: the MongoDB URI property, Jackson 3 Kafka serializers, and the
module-specific starter names.
