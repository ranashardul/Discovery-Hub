# Ingestion Service — Architecture and Code Guide

This document explains how the service is built: the architecture, what
every file does, and why each technology is used.

Related documents:

-   `RUNNING.md` — step-by-step instructions to run the service
-   `ingestion-service-README.md` — API reference and operations manual
-   `discovery-hub-project-status.md` — platform-wide project status

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

### Three design decisions worth knowing

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

## 4. File Guide

### Application entry point

  File                       Purpose
  -------------------------- --------------------------------------------------
  `IngestionApplication`     Boot entry point. `@EnableScheduling` activates the outbox sweep.

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

### `domain` — persisted model

  File                        Purpose
  --------------------------- --------------------------------------------------
  `MessageDocument`           The `messages` collection. Immutable `_id`, unique dedup key, sparse unique external ID, attachments, retention fields and outbox state.
  `AttachmentMetadata`        Embedded attachment record: id, filename, content type, size, SHA-256, bucket, key, URL.
  `IngestionRequestDocument`  The `ingestion_requests` collection. Idempotency guard and status registry; holds the assigned `messageId` and staged attachments.
  `StagedAttachment`          Reference to a binary sitting in the staging prefix. Travels inside the Kafka event.
  `IngestionStatus`           `RECEIVED`, `PROCESSING`, `INGESTED`, `FAILED`.
  `OutboxStatus`              `PENDING`, `PUBLISHED`.

### `repository` — data access

  File                           Purpose
  ------------------------------ -----------------------------------------------
  `MessageRepository`            Lookups by dedup key and external ID, plus outbox queries used by the publisher.
  `IngestionRequestRepository`   Lookups by dedup key and external ID for idempotency and status.

### `messaging` — event contracts

  File                       Purpose
  -------------------------- --------------------------------------------------
  `IngestionRequestedEvent`  Accepted request: content plus staged attachment references. Consumed by the worker.
  `MessageIngestedEvent`     Durable-storage notification: eventId, messageId, dedup key, occurredAt. Consumed by the search service.

### `service` — business logic

  File                        Purpose
  --------------------------- --------------------------------------------------
  `IngestionRequestService`   API-side orchestration: dedup, duplicate short-circuit, attachment staging, request registration, event publication. Rolls back staged objects if registration loses a race.
  `HashService`               Builds the canonical string and the SHA-256 dedup key.
  `S3StorageService`          All object-store access: key layout, upload, server-side copy, head, delete, URL construction, checksums, bucket bootstrap at startup.
  `AttachmentStorageService`  Turns staged attachments into durable objects and `AttachmentMetadata`; discards staged copies.
  `OutboxPublisher`           Publishes `message.ingested`, flips the outbox marker, and re-publishes anything left `PENDING` on a schedule.
  `InvalidAttachmentException` Raised for undecodable base64, surfaced as `400`.

### `worker` — storage half

  File                Purpose
  ------------------- --------------------------------------------------
  `IngestionWorker`   `@KafkaListener` on `ingestion.requested`. Skips completed requests, assigns the immutable message ID, materializes attachments, inserts the message, marks the request `INGESTED`, and hands off to the outbox. Rethrows failures so retry and dead-lettering apply.

### `config` — wiring

  File                  Purpose
  --------------------- --------------------------------------------------
  `KafkaConfig`         Topic definitions (including dead letters) and the error handler with exponential backoff plus `DeadLetterPublishingRecoverer`.
  `S3Config`            Builds the `S3Client` with optional endpoint override, path-style access and the default AWS credential chain.
  `StorageProperties`   Binds `app.storage.*`: bucket, region, endpoint, prefixes, public base URL.
  `MongoStartupCheck`   Logs the effective database, collections and indexes at startup, and warns when a unique index is missing.

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
  `IngestionApplicationTests`       Context startup.

Integration tests use the singleton container pattern deliberately.
Static `@Container` fields restart per test class while the Spring
context is cached, which leaves the producer pointing at a dead broker.

## 5. Data Stored

### `messages`

Durable message documents. Indexes: `_id`, unique `deduplicationKey`,
sparse unique `externalMessageId`, and helper indexes on
`communicationType`, `sender`, `messageTimestamp`, `threadId` and
`outboxStatus`.

### `ingestion_requests`

One document per accepted request. Indexes: `_id` (the request ID),
unique `deduplicationKey`, sparse unique `externalMessageId`. This is
where you look when a submission seems missing.

### Object store

``` text
staging/{requestId}/{index}-{filename}                              transient
messages/{messageId}/attachments/{attachmentId}/{filename}          durable
```

## 6. Failure Behaviour

  Failure                      What happens
  ---------------------------- --------------------------------------------------
  Invalid request              `400` with field-level errors; nothing is written.
  Undecodable attachment       `400`; staged objects from the same request are removed.
  Concurrent duplicate         Unique index rejects the loser, which resolves to the winning document.
  Kafka down at accept time    The request is registered and the failure logged; the worker picks it up when the event is replayed.
  Worker failure               Request marked `FAILED` with the error, retried with backoff, then routed to `ingestion.requested.dlt`. The message ID is preserved.
  Kafka down after storage     Outbox marker stays `PENDING`; the scheduled sweep republishes.
  S3 unavailable               Upload or copy fails, the request goes `FAILED`, and the event is retried.

Operational queries:

``` javascript
db.ingestion_requests.find({ status: "FAILED" }, { lastError: 1, attempts: 1 })
db.messages.countDocuments({ outboxStatus: "PENDING" })
```
