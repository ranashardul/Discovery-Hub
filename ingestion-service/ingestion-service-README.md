# Ingestion Service

The Ingestion Service is the entry point for communication records
entering Discovery Hub. Ingestion is asynchronous: the API accepts and
publishes a request, and a worker performs durable storage.

``` text
Corpus generator / Angular UI
              |
              v
       Ingestion API            (validate, stage attachments, register request)
              |
              v
   Kafka: ingestion.requested
              |
              v
       Ingestion Worker         (immutable message ID, durable storage)
              |
       +------+------+
       |             |
       v             v
   MongoDB          S3          (message data / attachment binaries)
       |
       v
   Kafka: message.ingested      (published from the outbox)
              |
              v
       Search Service
              |
              v
        Elasticsearch
```

## Responsibilities

### Ingestion API

-   Accepts communication records through a REST API
-   Validates request data
-   Generates a SHA-256 deduplication key
-   Uploads attachment binaries to a staging prefix in S3
-   Registers the request for idempotency and status lookup
-   Publishes `ingestion.requested`
-   Returns `202 Accepted`

The API does not write the final message document and does not own the
durable attachment objects.

### Ingestion Worker

-   Consumes `ingestion.requested`
-   Skips requests that are already ingested
-   Assigns the immutable message ID once and reuses it on every retry
-   Copies staged attachments to their durable, message-scoped keys
-   Persists the message and attachment metadata in MongoDB
-   Publishes `message.ingested` through the outbox
-   Retries failures and dead-letters exhausted events

The service does not own search indexes, cases, legal holds, or export
packages. Those capabilities belong to other services.

## Technology Stack

-   Java 21
-   Spring Boot
-   Spring Web
-   Spring Validation
-   Spring Data MongoDB
-   Spring Kafka
-   AWS SDK v2 for S3
-   Maven
-   MongoDB (local container or Atlas)
-   Apache Kafka
-   Docker and Docker Compose
-   MinIO for local S3-compatible object storage
-   Testcontainers for integration tests

## Service Port

The application runs on:

``` text
http://localhost:8081
```

## API Endpoints

### Health Check

``` http
GET /actuator/health
```

Example:

``` bash
curl http://localhost:8081/actuator/health
```

Expected response:

``` json
{
  "status": "UP"
}
```

### Ingest a Message

``` http
POST /api/ingestion/messages
Content-Type: application/json
```

Example request:

``` bash
curl -i -X POST \
  http://localhost:8081/api/ingestion/messages \
  -H "Content-Type: application/json" \
  -d '{
    "communicationType": "EMAIL",
    "sender": "alice.sharma@example-corp.test",
    "recipients": [
      "bob.patel@example-corp.test"
    ],
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
  }'
```

The response is HTTP `202 Accepted`:

``` json
{
  "requestId": "generated-request-uuid",
  "deduplicationKey": "generated-sha256-key",
  "status": "RECEIVED",
  "duplicate": false,
  "messageId": null
}
```

`messageId` is assigned by the worker, so it is `null` until processing
completes. `duplicate` is `true` when the message was already submitted;
the response then carries the original `requestId`, `status` and
`messageId`, and no new document, S3 object or event is created.

### Look Up Request Status

``` http
GET /api/ingestion/requests/{requestId}
GET /api/ingestion/requests?deduplicationKey={key}
GET /api/ingestion/requests?externalMessageId={id}
```

``` bash
curl http://localhost:8081/api/ingestion/requests/{requestId}
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

Statuses are `RECEIVED`, `PROCESSING`, `INGESTED` and `FAILED`. A
`FAILED` request keeps its `messageId`, so it can be retried without
creating a new message identity. Unknown identifiers return HTTP `404`
with the structured error body.

### Validation Errors

Invalid requests return HTTP `400` with a structured error body:

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

## Request Fields

  -----------------------------------------------------------------------------
  Field                 Type                          Required Description
  --------------------- ---------------- --------------------- ----------------
  `communicationType`   string                             Yes Type of
                                                               communication,
                                                               such as `EMAIL`

  `sender`              string                             Yes Sender email
                                                               address

  `recipients`          array of strings                   Yes Recipient email
                                                               addresses

  `subject`             string                             Yes Message subject

  `body`                string                             Yes Message body

  `messageTimestamp`    ISO-8601                           Yes Original
                        timestamp                              communication
                                                               timestamp

  `threadId`            string                              No Optional
                                                               conversation or
                                                               thread
                                                               identifier

  `externalMessageId`   string                              No Stable source
                                                               system
                                                               identifier used
                                                               for idempotency

  `attachments`         array of objects                    No Attachment
                                                               binaries
  -----------------------------------------------------------------------------

Each attachment object contains:

  Field             Type                        Required Description
  ----------------- ------------------ ----------------- -------------------
  `filename`        string                           Yes Original file name
  `contentType`     string                            No MIME type
  `contentBase64`   string                           Yes Base64 binary

`communicationType` accepts `EMAIL` and `CHAT`.

Attachment binaries are uploaded to S3 by the API before the event is
published, so Kafka events stay small and never carry binary content.

## Local Infrastructure

The local Docker Compose infrastructure contains:

  Service             Container name                Port
  ------------------- ------------------------ ---------
  MongoDB             `stown-mongodb`            `27017`
  Kafka               `stown-kafka`               `9092`
  MinIO API           `stown-minio`               `9000`
  MinIO Console       `stown-minio`               `9001`
  Kafka UI            `stown-kafka-ui`            `8085`
  Elasticsearch       `stown-elasticsearch`       `9200`
  Ingestion Service   `stown-ingestion-service`   `8081`
  Search Service      `stown-search-service`      `8082`

Kafka advertises two listeners:

-   `localhost:9092` for clients running on the host
-   `kafka:19092` for containers on the compose network

Start everything, including the ingestion service, from the
infrastructure directory:

``` bash
docker compose up -d --build
```

Check the containers:

``` bash
docker compose ps
```

View Kafka logs:

``` bash
docker compose logs --tail=100 kafka
```

View MongoDB logs:

``` bash
docker compose logs --tail=100 mongodb
```

## Running the Service Locally

The service is part of the compose stack, so the simplest option is:

``` bash
cd infrastructure
docker compose up -d --build
```

To run it on the host instead, start the infrastructure only and then
build and run from the ingestion service directory:

``` bash
docker compose up -d mongodb kafka minio kafka-ui elasticsearch
```

``` bash
./mvnw clean package
./mvnw spring-boot:run
```

When running on the host, point the service at the host-facing ports and
supply object-store credentials:

``` bash
export MONGODB_URI=mongodb://localhost:27017/legal_discovery
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export S3_ENDPOINT=http://localhost:9000
export S3_BUCKET=discovery-hub-attachments
export AWS_ACCESS_KEY_ID=<your-access-key>
export AWS_SECRET_ACCESS_KEY=<your-secret-key>
export AWS_REGION=ap-south-1
```

See `.env.example` in the repository root for the full list. The service
starts on port `8081`. Only one of the two options can run at a time
because both bind port `8081`.

## MongoDB Configuration

The intended MongoDB database is:

``` text
legal_discovery
```

Local configuration:

``` yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/legal_discovery
  data:
    mongodb:
      auto-index-creation: true
```

Spring Boot 4 reads the connection URI from `spring.mongodb.*`. Settings
placed under `spring.data.mongodb.uri` are ignored, which makes the
application fall back to the default `mongodb://localhost/test`. Only
Spring Data specific options such as `auto-index-creation` belong under
`spring.data.mongodb`.

The URI comes from the `MONGODB_URI` environment variable, which defaults
to the local container. To use an Atlas cluster, set it in `.env`:

``` text
MONGODB_URI=mongodb+srv://<username>:<password>@<cluster-host>/legal_discovery?retryWrites=true&w=majority
```

Credentials must never be committed. `.env` is git-ignored;
`.env.example` documents every variable with placeholders.

Check the database manually:

``` bash
docker exec -it stown-mongodb mongosh
```

Then run:

``` javascript
use legal_discovery
show collections
db.messages.countDocuments()
db.messages.findOne()
db.ingestion_requests.countDocuments({ status: "FAILED" })
```

The expected collections are:

``` text
messages             durable message documents
ingestion_requests   request registry, idempotency and status
```

## Kafka Configuration

The local Kafka broker is:

``` text
localhost:9092
```

The service uses four topics:

``` text
ingestion.requested       accepted requests, consumed by the worker
ingestion.requested.dlt   events that exhausted their retries
message.ingested          durably stored messages, consumed by search
message.ingested.dlt      dead letters from downstream consumers
```

`ingestion.requested` carries the message content and staged attachment
references, never binaries:

``` json
{
  "requestId": "request-uuid",
  "externalMessageId": "corpus-000001",
  "deduplicationKey": "sha256-key",
  "communicationType": "EMAIL",
  "sender": "alice.sharma@example-corp.test",
  "recipients": ["bob.patel@example-corp.test"],
  "subject": "Project Atlas: budget review",
  "body": "Please review the latest budget information.",
  "messageTimestamp": "2026-09-08T03:00:00Z",
  "threadId": "thread-00001",
  "attachments": [
    {
      "filename": "budget-review.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 101719,
      "sha256": "sha256-of-the-binary",
      "stagingBucket": "discovery-hub-attachments",
      "stagingKey": "staging/request-uuid/0-budget-review.pdf"
    }
  ]
}
```

`message.ingested` is published only after the message is durably stored:

``` json
{
  "eventId": "event-uuid",
  "messageId": "message-uuid",
  "deduplicationKey": "sha256-key",
  "occurredAt": "2026-09-08T03:00:00Z"
}
```

Kafka uses JSON serialization for event values through
`JacksonJsonSerializer`. Spring Boot 4 ships Jackson 3, and the older
`JsonSerializer` uses a Jackson 2 mapper without JSR-310 support, which
fails on the `Instant` field of the event.

## Deduplication

The service generates a SHA-256 key from canonicalized message data.

The canonical input includes:

-   Communication type
-   Sender
-   Sorted recipients
-   Subject
-   Body
-   Message timestamp
-   Thread ID
-   External message ID

`externalMessageId` participates in the canonical form on purpose. A
source-system identifier distinguishes two genuinely different messages
that happen to carry identical content, while re-submitting the same
source message still produces the same key and stays idempotent.

Unique MongoDB indexes are created automatically from the
`@Indexed(unique = true)` mappings because
`spring.data.mongodb.auto-index-creation` is enabled. The equivalent
manual commands are:

``` javascript
db.messages.createIndex(
  { deduplicationKey: 1 },
  { unique: true, name: "deduplicationKey_unique" }
)

db.messages.createIndex(
  { externalMessageId: 1 },
  { unique: true, sparse: true, name: "externalMessageId_unique" }
)

db.ingestion_requests.createIndex(
  { deduplicationKey: 1 },
  { unique: true, name: "request_deduplicationKey_unique" }
)

db.ingestion_requests.createIndex(
  { externalMessageId: 1 },
  { unique: true, sparse: true, name: "request_externalMessageId_unique" }
)
```

These protect against concurrent duplicate requests: the losing writer
receives a duplicate-key error and resolves to the winning document.

A duplicate request creates no second message document, no second S3
object and no second logical event. It returns the original `requestId`
and `messageId`.

## Data Model

A message document currently contains fields similar to:

``` json
{
  "_id": "message-uuid",
  "deduplicationKey": "sha256-key",
  "externalMessageId": "corpus-000001",
  "requestId": "request-uuid",
  "communicationType": "EMAIL",
  "sender": "alice.sharma@example-corp.test",
  "recipients": ["bob.patel@example-corp.test"],
  "subject": "Project Atlas: budget review",
  "body": "Message body",
  "messageTimestamp": "2026-09-08T03:00:00Z",
  "threadId": "thread-00001",
  "attachments": [
    {
      "attachmentId": "att-001",
      "filename": "budget-review.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 101719,
      "sha256": "sha256-of-the-binary",
      "s3Bucket": "discovery-hub-attachments",
      "s3Key": "messages/message-uuid/attachments/att-001/budget-review.pdf",
      "s3Url": "http://localhost:9000/discovery-hub-attachments/messages/..."
    }
  ],
  "createdAt": "2026-09-08T03:00:00Z",
  "retentionUntil": "2027-09-08T03:00:00Z",
  "holdCount": 0,
  "dispositionStatus": "ACTIVE",
  "outboxStatus": "PUBLISHED",
  "outboxPublishedAt": "2026-09-08T03:00:00Z",
  "outboxAttempts": 0
}
```

`_id` is the immutable message ID. It is also the Elasticsearch document
ID and part of every attachment object key.

The current one-year retention value is a temporary placeholder. Final
retention must be driven by the retention policy and legal-hold rules.

## Attachment Storage

Binaries live in S3 (MinIO locally); MongoDB stores only metadata and the
object location. Object keys follow:

``` text
messages/{messageId}/attachments/{attachmentId}/{filename}
```

The flow avoids putting binaries on Kafka and avoids the API owning
durable objects:

1.  The API decodes the base64 content and uploads it to
    `staging/{requestId}/{index}-{filename}`.
2.  The event carries only the staging references.
3.  The worker performs a server-side copy to the durable key.
4.  The staged object is deleted once the message is stored.

Configuration is environment driven:

``` yaml
app:
  storage:
    bucket: ${S3_BUCKET}
    region: ${AWS_REGION:ap-south-1}
    endpoint: ${S3_ENDPOINT:}
```

Leave `S3_ENDPOINT` empty for real AWS S3. Credentials come from the
standard AWS chain (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
instance profiles), so nothing is stored in source.

`AWS_REGION` must match the bucket's region or S3 rejects requests with
`PermanentRedirect`. The IAM identity needs `s3:PutObject`,
`s3:GetObject` (the server-side copy reads the source object) and
`s3:DeleteObject` (removing the staged copy).

Retrieval, the full IAM policy and recommended archival bucket settings
are documented in `../docs/s3-archival.md`.

## Reliable Publication

`message.ingested` is published from an outbox marker written together
with the message document. Because both live in the same document, the
write is atomic without a multi-document transaction, so it works on a
standalone container and on Atlas.

-   `outboxStatus: PENDING` is written with the message
-   The publisher sends the event and flips it to `PUBLISHED`
-   A scheduled sweep republishes anything still `PENDING`
-   Delivery is at-least-once, so consumers key on `messageId` and must
    be idempotent

Failed `ingestion.requested` processing is retried with exponential
backoff and then routed to `ingestion.requested.dlt`. The request keeps
its `messageId` and `FAILED` status, so replaying the event does not
create a new message identity.

Find work that still needs attention:

``` javascript
db.messages.countDocuments({ outboxStatus: "PENDING" })
db.ingestion_requests.find({ status: "FAILED" })
```

## Testing

Run the Maven test suite:

``` bash
mvn clean test
```

The suite contains 17 tests:

-   `HashServiceTest`: deduplication-key unit tests
-   `IngestionApiIntegrationTest`: end-to-end tests covering asynchronous
    acceptance, worker storage, attachment upload to the object store,
    idempotent re-submission, message-ID stability, status lookup, unique
    indexes, validation errors and both Kafka events
-   `IngestionApplicationTests`: context startup

Integration tests use Testcontainers and start real MongoDB, Kafka and
MinIO containers, so Docker must be running. The containers are started
once per JVM because the Spring test context is cached across test
classes.

Perform a health check:

``` bash
curl http://localhost:8081/actuator/health
```

Submit a message and follow it through the pipeline:

``` bash
curl -i -X POST \
  http://localhost:8081/api/ingestion/messages \
  -H "Content-Type: application/json" \
  -d '{
    "communicationType": "EMAIL",
    "sender": "alice.sharma@example-corp.test",
    "recipients": ["bob.patel@example-corp.test"],
    "subject": "Test message",
    "body": "Testing ingestion.",
    "messageTimestamp": "2026-09-08T03:00:00Z",
    "threadId": "test-thread",
    "externalMessageId": "manual-test-1"
  }'
```

``` bash
curl "http://localhost:8081/api/ingestion/requests?externalMessageId=manual-test-1"
```

Submit the same request again and verify that `duplicate` is `true`, the
same `messageId` comes back, and no duplicate record is created.

For a realistic corpus, use the generator in `corpus-generator/`.

## Current Limitations

The following work is not yet complete:

-   Final retention-policy implementation
-   Legal-hold integration
-   API documentation generation
-   Multipart upload endpoint for very large attachments (binaries are
    currently sent as base64 in the JSON request)
-   Automated replay tooling for `ingestion.requested.dlt`

## Troubleshooting

### Application returns 404

Use the complete endpoint:

``` text
POST /api/ingestion/messages
```

The base path `/api/ingestion` does not itself define a POST endpoint.

### Kafka serialization error

If the logs contain `Can't serialize data [MessageIngestedEvent...]`
caused by `Java 8 date/time type java.time.Instant not supported by
default`, the producer is using the Jackson 2 serializer. Verify that the
producer value serializer is:

``` yaml
spring:
  kafka:
    producer:
      value-serializer: org.springframework.kafka.support.serializer.JacksonJsonSerializer
```

### MongoDB contains data in `test`

The application is using the default URI because the configured one sits
under the wrong prefix. Verify that the configuration is:

``` yaml
spring:
  mongodb:
    uri: mongodb://localhost:27017/legal_discovery
```

The effective database and indexes are logged at startup:

``` text
MongoDB ready database=legal_discovery
Collection messages documents=0 indexes=[_id_, deduplicationKey_unique (unique), ...]
Collection ingestion_requests documents=0 indexes=[_id_, ...]
```

### A request stays in RECEIVED

The worker is not consuming. Check that the broker is reachable and that
the consumer joined its group:

``` bash
docker compose logs --tail=100 ingestion-service
docker exec stown-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:19092 --describe --group ingestion-service
```

### A request is FAILED

The event was retried and dead-lettered. Inspect the error, fix the
cause, then replay from the dead-letter topic; the message ID is
preserved:

``` javascript
db.ingestion_requests.find({ status: "FAILED" }, { lastError: 1, attempts: 1 })
```

### Attachment upload fails with 403 or NoSuchBucket

The AWS credential chain found no usable credentials, or the bucket does
not exist. Verify `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`,
`S3_BUCKET` and `S3_ENDPOINT`. With MinIO the access key and secret must
match `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD`.

### Port 8081 is already in use

Docker Compose fails with `bind: address already in use` when a local
`mvn spring-boot:run` instance is still running. Stop the local process
or the container, but not both at once.

### View application logs

When running with Maven, logs appear in the terminal:

``` bash
mvn spring-boot:run
```

When running in Docker, use:

``` bash
docker logs <container-name>
```

## Suggested Next Steps

1.  Replace the placeholder retention value with the retention policy.
2.  Integrate legal-hold checks before disposition.
3.  Add a multipart upload endpoint for very large attachments.
4.  Add replay tooling for `ingestion.requested.dlt`.
5.  Add API documentation generation.
