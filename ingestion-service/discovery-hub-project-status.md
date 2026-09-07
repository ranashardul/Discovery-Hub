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

The current implementation is still in the early foundation stage. The
ingestion service is the first working backend service.

## 2. Architecture Agreed So Far

``` text
Corpus / Angular UI
        |
        v
Ingestion Service
        |
        +--> MongoDB: message source of truth
        |
        +--> MinIO/S3: attachment storage
        |
        +--> Kafka: message.ingested event
                    |
                    v
              Search Service
                    |
                    v
              Elasticsearch
```

The planned backend services are:

1.  Ingestion Service
2.  Search Service
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

Current exposed ports:

  Component          Port
  --------------- -------
  MongoDB           27017
  Kafka              9092
  MinIO API          9000
  MinIO Console      9001
  Kafka UI           8085

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
-   REST controller
-   Request validation
-   Ingestion service
-   SHA-256 deduplication-key generation
-   MongoDB repository
-   Message document model
-   Kafka event model
-   Kafka topic configuration
-   Kafka JSON serialization
-   Health endpoint

The service exposes:

``` text
POST /api/ingestion/messages
```

The health endpoint is:

``` text
GET /actuator/health
```

The health endpoint returned an `UP` status.

### 3.5 Ingestion Request Model

The current request accepts:

-   `communicationType`
-   `sender`
-   `recipients`
-   `subject`
-   `body`
-   `messageTimestamp`
-   `threadId`

Validation currently includes:

-   Required fields must be present.
-   Sender must be an email address.
-   Recipient values must be email addresses.
-   Timestamp must be present.

### 3.6 Deduplication

The service calculates a SHA-256 deduplication key from canonicalized
message data.

The canonical input includes:

-   Communication type
-   Sender
-   Sorted recipients
-   Subject
-   Body
-   Message timestamp
-   Thread ID

The service first checks for an existing message with the same
deduplication key. A duplicate request returns the existing message
instead of creating another message.

A unique MongoDB index should be maintained on:

``` javascript
db.messages.createIndex(
  { deduplicationKey: 1 },
  { unique: true }
)
```

### 3.7 Kafka Event

After a message is saved, the service publishes a `MessageIngestedEvent`
to:

``` text
message.ingested
```

The event currently contains:

-   `eventId`
-   `messageId`
-   `deduplicationKey`
-   `occurredAt`

A Kafka serialization issue was found and corrected. The producer now
uses Spring Kafka JSON serialization instead of `StringSerializer` for
the event value.

### 3.8 Successful End-to-End Test

A POST request to the ingestion endpoint succeeded and returned:

-   HTTP status `200`
-   A generated `messageId`
-   A generated `deduplicationKey`
-   Status `INGESTED`

This confirms that the following path is working:

``` text
HTTP request
    -> Request validation
    -> Deduplication-key generation
    -> MongoDB save
    -> Kafka event serialization
    -> Kafka event publication
    -> HTTP response
```

## 4. Current Working Components

The following components are currently working or substantially working:

-   Java 21 toolchain
-   Maven build
-   Spring Boot application startup
-   Ingestion REST endpoint
-   Request validation
-   SHA-256 deduplication-key generation
-   MongoDB connectivity
-   Kafka connectivity
-   Kafka KRaft broker
-   Kafka JSON event serialization
-   `message.ingested` event publication
-   Actuator health endpoint
-   Docker Compose infrastructure

## 5. Known Issue: MongoDB Database Selection

There is an unresolved configuration issue involving the MongoDB
database name.

The intended database is:

``` text
legal_discovery
```

However, diagnostic checks showed that the application was connecting to
the MongoDB server and using the `test` database. The `legal_discovery`
database was empty.

MongoDB does not display an empty database in `show dbs`, so the
following behavior is expected when no data exists in it:

``` text
admin
config
local
test
```

The application's effective database configuration must still be
verified.

Recommended configuration:

``` yaml
spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      database: legal_discovery
```

After changing the configuration:

1.  Run a clean build.
2.  Restart the Spring Boot application.
3.  Submit a new ingestion request.
4.  Check `legal_discovery.messages`.
5.  Confirm that the message is not being written to `test.messages`.

Useful MongoDB checks:

``` javascript
use legal_discovery
show collections
db.messages.countDocuments()
db.messages.findOne()
```

Temporary diagnostic logging can print the effective database selected
by Spring Data MongoDB.

## 6. Important Technical Risks Identified

### 6.1 Database Save and Kafka Publication Are Not Atomic

The current flow saves the MongoDB document and then publishes the Kafka
event.

If MongoDB succeeds but Kafka publication fails:

-   The message exists in MongoDB.
-   The API may return an error.
-   A retry may find the existing message.
-   The retry may return early without publishing the missing event.

This can cause an event to be lost.

The recommended next improvement is a transactional outbox pattern:

``` text
Save message + outbox event in MongoDB
              |
              v
Outbox publisher
              |
              v
Kafka
```

### 6.2 Kafka Delivery Is Asynchronous

The current service calls `kafkaTemplate.send(...)` without waiting for
the send result.

The implementation should eventually add:

-   Send callbacks or result handling
-   Retry policy
-   Error logging
-   Dead-letter handling where appropriate
-   Outbox-based reliable publication

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

### 6.4 Attachments Are Not Yet Implemented

The current ingestion endpoint handles message metadata and body
content. Attachment handling still needs to be added.

The expected approach is:

-   Store attachment binaries in MinIO/S3.
-   Store attachment metadata and object keys in MongoDB.
-   Calculate checksums for attachment integrity.
-   Publish attachment-related metadata in ingestion events.

## 7. Remaining Work

### Immediate Ingestion-Service Tasks

-   Resolve the MongoDB database-selection issue.
-   Add `@Document(collection = "messages")` to the message document.
-   Create and verify the unique deduplication index.
-   Add integration tests for MongoDB persistence.
-   Add a duplicate-ingestion test.
-   Add invalid-request validation tests.
-   Add Kafka event-consumption verification.
-   Add reliable Kafka publication handling.
-   Add attachment upload support.
-   Add S3/MinIO object metadata.
-   Add structured logging.
-   Add consistent error responses.
-   Add API documentation.

### Archival and Retention Tasks

-   Define the archival data model.
-   Store attachments in MinIO/S3.
-   Add retention-policy configuration.
-   Implement retention evaluation.
-   Implement legal-hold checks before disposition.
-   Implement disposition workflow.
-   Record disposition actions in an audit trail.
-   Add scheduled retention processing.
-   Add tests proving that legal holds prevent deletion.

### Search-Service Tasks

-   Create the search service.
-   Consume `message.ingested`.
-   Index messages in Elasticsearch using `messageId` as the document
    ID.
-   Implement search APIs.
-   Support rebuilding the Elasticsearch projection from MongoDB or
    events.
-   Add search integration tests.

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

-   Add a Dockerfile for the ingestion service.
-   Add the ingestion service to Docker Compose.
-   Ensure the service can connect to containers by service name.
-   Add health checks.
-   Add environment-variable configuration.
-   Verify single-command startup.

## 8. Recommended Next Execution Order

1.  Fix and verify MongoDB database selection.
2.  Add MongoDB collection and unique-index configuration.
3.  Add ingestion integration tests.
4.  Add attachment storage in MinIO.
5.  Add outbox-based Kafka publication.
6.  Containerize the ingestion service.
7.  Verify the complete Docker-based ingestion flow.
8.  Begin the archival and retention service.
9.  Begin the search service.
10. Implement legal holds before implementing final disposition
    behavior.

## 9. Definition of Done for the Ingestion Foundation

The ingestion foundation should be considered complete when:

-   The service starts through Docker Compose.
-   The health endpoint reports healthy.
-   A valid message can be ingested.
-   The message is stored in `legal_discovery.messages`.
-   Duplicate messages do not create duplicate records.
-   The unique deduplication index exists.
-   Attachments are stored in MinIO/S3.
-   A `message.ingested` event is published reliably.
-   Kafka publication failures are retried or recovered through an
    outbox.
-   Invalid requests return clear validation errors.
-   Integration tests pass.
-   Logs and configuration are suitable for local development.
