# Discovery Hub --- Project Status

## 1. Project Overview

Discovery Hub is a legal-discovery platform built as a set of
independently deployable microservices. The current implementation work
has focused on the ingestion service and the local infrastructure
required to run and test it.

The intended platform includes:

-   Ingestion
-   Archival and retention
-   Search
-   Case management
-   Legal holds
-   Export
-   Auditability

The ingestion path and the search path are implemented and verified
end to end against a 10,000-message synthetic corpus. Case management,
legal holds, export and disposition are still outstanding.

### Verified corpus run

A 10,000-message corpus was generated and ingested with zero failures:

  Metric                              Result
  ----------------------------------- --------------------------
  Messages submitted                  10,000 (0 failed)
  Submission rate                     100 requests/second
  Requests in `INGESTED`              10,001 (incl. 1 probe)
  Requests in `FAILED`                0
  EMAIL / CHAT                        6,435 / 3,568
  Messages with attachments           780 (7.8%)
  Attachment records                   901
  S3 objects                           901 (staging prefix empty)
  Distinct custodians (senders)        27 (25 corpus + 2 legacy)
  Distinct threads                     2,863
  Outbox events pending                0
  Elasticsearch documents             10,003
  Accept to searchable                0.32 s (target: under 30 s)
  Dead letters on ingestion path       0

## 2. Architecture Agreed So Far

``` text
Corpus generator / Angular UI
              |
              v
       Ingestion API              staging upload, request registry
              |
              v
   Kafka: ingestion.requested
              |
              v
       Ingestion Worker           immutable message ID
              |
       +------+------+
       |             |
       v             v
   MongoDB          S3            message data / attachment binaries
       |
       v
   Kafka: message.ingested        published from the outbox
              |
              v
       Search Service
              |
              v
        Elasticsearch
              |
              v
         Search API
```

Ingestion is asynchronous and decoupled: the API only accepts, stages
and publishes, while the worker owns durable storage. Attachment
binaries never travel through Kafka.

The planned backend services are:

1.  Ingestion Service (implemented)
2.  Search Service (implemented)
3.  Case and Legal Hold Service
4.  Export and Retention Service

Each service should own its data and expose APIs only for the
capabilities it owns. Kafka is used for asynchronous communication
between services.

## 3. Completed Work

### 3.1 Project and Architecture

-   Reviewed the product requirements and identified the major
    microservices.
-   Identified the need for:
    -   Independent deployability
    -   Separate data ownership
    -   Asynchronous events
    -   Auditability
    -   Retention enforcement
    -   Legal-hold protection
    -   Containerized startup
-   Defined MongoDB as the initial source of truth for ingested
    messages.
-   Defined Elasticsearch as a rebuildable search projection rather than
    the primary source of truth.
-   Defined Kafka as the event bus between services.
-   Decided to use MinIO locally as an S3-compatible object store.

### 3.2 Local Infrastructure

The Docker Compose infrastructure has been created and started.

Current containers:

-   `stown-mongodb`
-   `stown-kafka`
-   `stown-minio`
-   `stown-kafka-ui`
-   `stown-ingestion-service`

Current exposed ports:

  Component          Port
  --------------- -------
  MongoDB           27017
  Kafka              9092
  MinIO API          9000
  MinIO Console      9001
  Kafka UI           8085
  Ingestion          8081

Kafka advertises `localhost:9092` for host clients and `kafka:19092` for
containers on the compose network.

Kafka was initially blocked by image and configuration issues. It is now
running successfully in KRaft mode using Apache Kafka `3.7.2`.

Kafka startup logs confirmed:

-   KRaft mode is enabled.
-   The broker transitioned to `STARTED`.
-   Kafka is listening on `0.0.0.0:9092`.
-   The Kafka server started successfully.

### 3.3 Java and Maven

-   Java 21 is installed and available.
-   Maven is using Java 21.
-   Initial Java package-name mismatches were corrected.
-   Missing ingestion classes were created.
-   The project successfully passed the Maven build and test command.

### 3.4 Ingestion Service

The Spring Boot ingestion service is running on port `8081`.

Implemented pieces include:

-   Spring Boot application
-   REST controller returning `202 Accepted`
-   Request validation, including attachment validation
-   Request registry backing idempotency and status lookup
-   SHA-256 deduplication-key generation
-   S3 staging upload in the API
-   Kafka producer for `ingestion.requested`
-   Kafka worker consuming `ingestion.requested`
-   Immutable message-ID assignment
-   Server-side attachment copy to durable object keys
-   MongoDB repositories, message and request documents
-   Outbox-based `message.ingested` publication with a scheduled sweep
-   Retry with exponential backoff and a dead-letter topic
-   Structured error responses
-   Health endpoint

The service exposes:

``` text
POST /api/ingestion/messages
GET  /api/ingestion/requests/{requestId}
GET  /api/ingestion/requests?deduplicationKey=...
GET  /api/ingestion/requests?externalMessageId=...
GET  /actuator/health
```

The health endpoint returns `UP`.

### 3.5 Ingestion Request Model

The request accepts:

-   `communicationType` (`EMAIL` or `CHAT`)
-   `sender`
-   `recipients`
-   `subject`
-   `body`
-   `messageTimestamp`
-   `threadId` (optional)
-   `externalMessageId` (optional)
-   `attachments` (optional, base64 binaries)

Validation includes:

-   Required fields must be present.
-   Sender must be an email address.
-   Recipient values must be email addresses and the list must not be
    empty.
-   Timestamp must be present.
-   Attachment filenames and base64 content must be present and
    decodable.

### 3.6 Deduplication and Idempotency

The service calculates a SHA-256 deduplication key from canonicalized
message data. The canonical input includes communication type, sender,
sorted recipients, subject, body, message timestamp, thread ID and
external message ID.

Duplicate submissions are resolved before any write, and the unique
indexes below make the guarantee hold under concurrency:

``` javascript
db.messages.createIndex({ deduplicationKey: 1 }, { unique: true })
db.messages.createIndex({ externalMessageId: 1 }, { unique: true, sparse: true })
db.ingestion_requests.createIndex({ deduplicationKey: 1 }, { unique: true })
db.ingestion_requests.createIndex({ externalMessageId: 1 }, { unique: true, sparse: true })
```

A duplicate request creates no second document, no second S3 object and
no second logical event. Statuses are `RECEIVED`, `PROCESSING`,
`INGESTED` and `FAILED`; a `FAILED` request keeps its message ID and can
be retried without creating a new identity.

### 3.7 Kafka Events

`ingestion.requested` carries the message content and staged attachment
references. `message.ingested` is published from the outbox after
durable storage and contains `eventId`, `messageId`, `deduplicationKey`
and `occurredAt`.

Both topics have dead-letter counterparts. A Kafka serialization issue
was found and corrected: the producer uses `JacksonJsonSerializer`
(Jackson 3) for event values. See section 5.1.

### 3.8 Search Service

A new `search-service` runs on port `8082`. It consumes
`message.ingested`, reads the message from MongoDB, and indexes it in
Elasticsearch using the message ID as the document ID, which makes
re-indexing idempotent.

It provides:

``` text
GET /api/search?q=...&communicationType=&sender=&threadId=&from=&size=
GET /api/search/messages/{messageId}
GET /api/search/stats
GET /actuator/health
```

Reliability features: retry with backoff, a `message.ingested.dlt`
dead-letter topic, failure records in `search_index_failures`, and a
scheduled reconciliation job that retries failures and indexes messages
that exist in MongoDB but are missing from Elasticsearch.

### 3.9 Corpus Generator

A Python generator lives in `corpus-generator/`. It produces fictional
custodians, EMAIL and CHAT messages with reused thread IDs, realistic
subjects and bodies, business-hours-weighted timestamps, and valid
attachment binaries across PDF, DOCX, XLSX, CSV, TXT, PNG and ZIP in
sizes from 5 KB to 1 MB. It is deterministic for a given seed, records
every submission in `messages.jsonl`, and supports `--dry-run`,
`--resume` and rate limiting.

### 3.10 Verified End-to-End Path

``` text
HTTP request
    -> Request validation
    -> Deduplication-key generation
    -> Attachment staging upload to S3
    -> Request registered, HTTP 202 returned
    -> Kafka ingestion.requested
    -> Worker assigns immutable message ID
    -> Attachment copied to durable S3 key, staged copy deleted
    -> MongoDB message document written with outbox marker
    -> Kafka message.ingested published, outbox marked PUBLISHED
    -> Search service indexes into Elasticsearch
    -> Message searchable through the search API
```

Measured accept-to-searchable latency with 10,000 documents indexed:
0.32 seconds.

## 4. Current Working Components

-   Java 21 toolchain and Maven build
-   Ingestion API and ingestion worker
-   Request validation and structured error responses
-   SHA-256 deduplication and unique-index enforcement
-   Immutable message IDs, stable across retries
-   MongoDB persistence (`messages`, `ingestion_requests`)
-   S3/MinIO attachment storage with checksums and object URLs
-   Kafka KRaft broker, four topics, JSON serialization
-   Outbox-based `message.ingested` publication
-   Retry with backoff and dead-letter routing
-   Search service, Elasticsearch indexing and search API
-   Corpus generator
-   Actuator health endpoints on both services
-   Docker Compose stack with health-gated startup
-   Environment-variable configuration with `.env.example` placeholders
-   Test suites: 17 ingestion tests, 13 search unit tests, 2 search
    integration tests

## 5. Resolved Issue: MongoDB Database Selection

The database-selection problem is resolved.

Root cause: the configuration used `spring.data.mongodb.uri`, but Spring
Boot 4 reads the connection URI from `spring.mongodb.*`. The property was
silently ignored and the driver fell back to the default
`mongodb://localhost/test`, which is why documents appeared in `test`.

Working configuration:

``` yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/legal_discovery
  data:
    mongodb:
      auto-index-creation: true
```

Only Spring Data specific options such as `auto-index-creation` belong
under `spring.data.mongodb`.

The effective database, collection and indexes are now logged on startup
by `MongoStartupCheck`:

``` text
MongoDB ready database=legal_discovery collection=messages documents=1
    indexes=[_id_, deduplicationKey_unique (unique)]
```

Useful MongoDB checks:

``` javascript
use legal_discovery
show collections
db.messages.countDocuments()
db.messages.getIndexes()
db.messages.findOne()
```

## 5.1 Resolved Issue: Kafka Event Serialization

Events were never reaching Kafka. The producer used
`org.springframework.kafka.support.serializer.JsonSerializer`, which is
backed by a Jackson 2 mapper without JSR-310 support, so the `Instant`
field of `MessageIngestedEvent` failed with:

``` text
Java 8 date/time type `java.time.Instant` not supported by default
```

Spring Boot 4 ships Jackson 3, so the producer now uses
`JacksonJsonSerializer`. Events are confirmed present on the
`message.ingested` topic.

Additionally, `kafkaTemplate.send(...)` can fail synchronously, which
previously turned an already-persisted ingestion into an HTTP 500. The
service now logs publication failures and completes the request, and the
send result is logged asynchronously with partition and offset.

## 6. Important Technical Risks Identified

### 6.1 Event Loss Between MongoDB and Kafka (resolved)

The previous flow saved the document and then published the event, so a
Kafka failure could lose the event permanently.

This is now handled by an outbox marker stored on the message document
itself:

``` text
Message document + outbox marker (single atomic document write)
              |
              v
Outbox publisher (immediate, then scheduled sweep)
              |
              v
Kafka
```

Because the marker lives inside the message document, the write is
atomic without a multi-document transaction, so the design works on a
standalone MongoDB container and on an Atlas replica set alike.

Trade-off: delivery is at-least-once, not exactly-once. The 10,000
message run produced 10,006 `message.ingested` records for 10,003
messages because a few events were republished by the sweep. Consumers
key on `messageId`, and Elasticsearch indexing is idempotent, so the
projection stayed at exactly 10,003 documents.

### 6.2 Kafka Delivery Reliability

Producer settings are `acks=all`, `retries=5` and idempotence enabled.
Send results are handled and logged with partition and offset. Consumer
failures are retried with exponential backoff and then routed to a
dead-letter topic.

Still outstanding:

-   Automated replay tooling for the dead-letter topics
-   Alerting on `outboxStatus: PENDING` backlog and `FAILED` requests

### 6.3 Retention Is Currently a Placeholder

The current message document sets:

``` text
retentionUntil = createdAt + 365 days
```

This is only a temporary implementation value.

The final system must derive retention from the product requirements and
case-specific policy. It must also ensure that:

-   Data is not disposed of before retention expires.
-   An active legal hold blocks disposition.
-   Disposition is auditable.
-   Legal holds override normal retention rules.

### 6.4 Attachment Transport

Attachments are implemented, but clients currently send binaries as
base64 inside the JSON request. That is fine for the corpus (1 MB cap)
and keeps Kafka events small, because the API stages the binary in S3
and the event carries only object references.

Still outstanding:

-   A multipart upload endpoint for very large attachments
-   A cleanup job for staged objects orphaned by requests that never
    reach the worker

## 7. Remaining Work

### Immediate Ingestion-Service Tasks

-   Add API documentation generation.
-   Add a multipart upload endpoint for large attachments.
-   Add replay tooling for `ingestion.requested.dlt`.
-   Add a cleanup job for orphaned staged objects.

Completed since the previous status:

-   MongoDB database selection resolved (`spring.mongodb.uri`).
-   Unique indexes on deduplication key and external message ID for both
    collections.
-   Kafka serialization fixed; events verified on the topic.
-   Asynchronous API and worker split with `ingestion.requested`.
-   Immutable message IDs, stable across retries.
-   S3 attachment storage with checksums, object keys and URLs in
    MongoDB.
-   Processing statuses and a status-lookup endpoint.
-   Outbox-based reliable publication plus retry and dead-letter
    handling.
-   Testcontainers integration tests covering the whole pipeline.
-   Structured error responses and structured logging.
-   Dockerfile and Docker Compose entries for both services.
-   Environment-variable configuration with `.env.example` placeholders.

### Archival and Retention Tasks

-   Define the archival data model.
-   Add retention-policy configuration.
-   Implement retention evaluation.
-   Implement legal-hold checks before disposition.
-   Implement disposition workflow.
-   Record disposition actions in an audit trail.
-   Add scheduled retention processing.
-   Add tests proving that legal holds prevent deletion.

### Search-Service Tasks

Completed:

-   Search service created on port `8082`.
-   Consumes `message.ingested`.
-   Indexes messages in Elasticsearch using `messageId` as the document
    ID.
-   Search API with full-text query, filters, pagination and highlights.
-   Reconciliation job rebuilds the projection from MongoDB.
-   Unit and Testcontainers integration tests.

Still outstanding:

-   A full reindex endpoint or command for bulk rebuilds.
-   Attachment content extraction and indexing.
-   Search result access control.

### Case and Legal Hold Tasks

-   Create PostgreSQL schema.
-   Implement cases and custodians.
-   Implement legal holds.
-   Publish legal-hold events.
-   Connect legal holds to retention and disposition checks.

### Export and Audit Tasks

-   Implement export jobs.
-   Generate export manifests.
-   Include checksums and metadata.
-   Store export packages in MinIO/S3.
-   Record export and disposition actions in an audit trail.

### Containerization Tasks

Completed:

-   Multi-stage Dockerfiles for the ingestion and search services.
-   Both services added to Docker Compose, along with Elasticsearch.
-   Services connect to MongoDB, Kafka, MinIO and Elasticsearch by
    container name, using a dedicated internal Kafka listener on
    `kafka:19092`.
-   Health checks for every container, with health-gated startup order.
-   Environment-variable configuration for all connection strings.
-   Single-command startup verified with `docker compose up -d --build`.

## 8. Recommended Next Execution Order

1.  Begin the archival and retention service.
2.  Implement legal holds before implementing final disposition
    behavior.
3.  Add case management.
4.  Add export jobs and the audit trail.
5.  Add the operational gaps: dead-letter replay, orphaned staging
    cleanup, bulk reindex, alerting.

The ingestion rework, S3 attachment storage, outbox publication, corpus
generation and the search service are complete and verified.

## 9. Definition of Done for the Ingestion Foundation

-   [x] The service starts through Docker Compose.
-   [x] The health endpoint reports healthy.
-   [x] A valid message can be ingested.
-   [x] The message is stored in `legal_discovery.messages`.
-   [x] Duplicate messages do not create duplicate records.
-   [x] The unique deduplication index exists.
-   [x] Attachments are stored in MinIO/S3.
-   [x] S3 URLs and metadata are stored in MongoDB.
-   [x] A `message.ingested` event is published.
-   [x] Kafka publication failures are recovered through an outbox.
-   [x] Failed processing is retried and dead-lettered.
-   [x] Message IDs are unique and immutable across retries.
-   [x] Invalid requests return clear validation errors.
-   [x] Integration tests pass.
-   [x] Logs and configuration are suitable for local development.

## 10. Corpus and Search Completion Checklist

### Corpus

-   [x] At least 10,000 messages generated (10,000)
-   [x] At least 20 fictional custodians (25)
-   [x] Email messages generated (6,435)
-   [x] Chat messages generated (3,568)
-   [x] Realistic subjects and bodies
-   [x] Realistic timestamps, business-hours weighted
-   [x] Participants and reused thread IDs (2,863 threads)
-   [x] At least 5% of messages have attachments (7.8%)
-   [x] Attachment types and sizes vary

### Ingestion

-   [x] API accepts requests asynchronously (HTTP 202)
-   [x] API publishes `ingestion.requested`
-   [x] Worker performs storage
-   [x] Message IDs are unique and immutable
-   [x] Duplicate requests are idempotent
-   [x] MongoDB persistence works
-   [x] S3 upload works (901 objects)
-   [x] S3 URLs are stored in MongoDB
-   [x] Kafka events are published reliably through the outbox
-   [x] Failed processing can be retried

### Search

-   [x] Search service consumes `message.ingested`
-   [x] Messages are indexed in Elasticsearch (10,003 documents)
-   [x] Elasticsearch document ID is the message ID
-   [x] Messages are searchable within 30 seconds (0.32 s measured)

### Verification

-   [x] MongoDB message count is correct
-   [x] Email and chat counts are correct
-   [x] Attachment count is at least 500 (901)
-   [x] Custodian count is at least 20 (25)
-   [x] S3 object count matches attachment records (901 = 901)
-   [x] Duplicate submission does not increase message count
-   [x] Search results contain ingested messages
