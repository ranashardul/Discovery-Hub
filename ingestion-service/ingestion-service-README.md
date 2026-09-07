# Ingestion Service

The Ingestion Service is the entry point for communication records
entering Discovery Hub. It validates incoming messages, calculates a
deterministic deduplication key, stores the message in MongoDB, and
publishes a `message.ingested` event to Kafka.

## Responsibilities

The service is responsible for:

-   Accepting communication records through a REST API
-   Validating request data
-   Generating a SHA-256 deduplication key
-   Preventing duplicate message records
-   Persisting message metadata in MongoDB
-   Publishing ingestion events to Kafka
-   Exposing a health endpoint

The service should not own search indexes, cases, legal holds, or export
packages. Those capabilities belong to other services.

## Technology Stack

-   Java 21
-   Spring Boot
-   Spring Web
-   Spring Validation
-   Spring Data MongoDB
-   Spring Kafka
-   Maven
-   MongoDB
-   Apache Kafka
-   Docker and Docker Compose
-   MinIO for local S3-compatible object storage

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
    "sender": "alice@example.com",
    "recipients": [
      "bob@example.com",
      "carol@example.com"
    ],
    "subject": "Project discussion",
    "body": "This is a sample message.",
    "messageTimestamp": "2026-09-07T18:30:00Z",
    "threadId": "thread-001"
  }'
```

Example successful response:

``` json
{
  "messageId": "generated-message-id",
  "deduplicationKey": "generated-sha256-key",
  "status": "INGESTED"
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
  -----------------------------------------------------------------------------

## Local Infrastructure

The local Docker Compose infrastructure contains:

  Service         Container name          Port
  --------------- ------------------ ---------
  MongoDB         `stown-mongodb`      `27017`
  Kafka           `stown-kafka`         `9092`
  MinIO API       `stown-minio`         `9000`
  MinIO Console   `stown-minio`         `9001`
  Kafka UI        `stown-kafka-ui`      `8085`

Start the infrastructure from the infrastructure directory:

``` bash
docker compose up -d
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

From the ingestion service directory:

``` bash
mvn clean package
```

Start the application:

``` bash
mvn spring-boot:run
```

The service should start on port `8081`.

## MongoDB Configuration

The intended MongoDB database is:

``` text
legal_discovery
```

Recommended local configuration:

``` yaml
spring:
  data:
    mongodb:
      host: localhost
      port: 27017
      database: legal_discovery
```

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
```

The expected collection is:

``` text
messages
```

## Kafka Configuration

The local Kafka broker is:

``` text
localhost:9092
```

The ingestion service publishes to:

``` text
message.ingested
```

The service also defines:

``` text
ingestion.requested
```

The `message.ingested` event currently contains:

``` json
{
  "eventId": "event-uuid",
  "messageId": "message-id",
  "deduplicationKey": "sha256-key",
  "occurredAt": "2026-09-07T18:30:00Z"
}
```

Kafka uses JSON serialization for event values.

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

A unique MongoDB index should exist on the deduplication key:

``` javascript
db.messages.createIndex(
  { deduplicationKey: 1 },
  { unique: true }
)
```

This protects against concurrent duplicate requests.

A duplicate request should return the existing message rather than
create another document.

## Data Model

A message document currently contains fields similar to:

``` json
{
  "id": "message-id",
  "deduplicationKey": "sha256-key",
  "communicationType": "EMAIL",
  "sender": "alice@example.com",
  "recipients": [
    "bob@example.com"
  ],
  "subject": "Project discussion",
  "body": "Message body",
  "messageTimestamp": "2026-09-07T18:30:00Z",
  "threadId": "thread-001",
  "createdAt": "2026-09-07T18:30:00Z",
  "retentionUntil": "2027-09-07T18:30:00Z",
  "holdCount": 0,
  "dispositionStatus": "ACTIVE"
}
```

The current one-year retention value is a temporary placeholder. Final
retention must be driven by the retention policy and legal-hold rules.

## Testing

Run the Maven test suite:

``` bash
mvn clean test
```

Perform a health check:

``` bash
curl http://localhost:8081/actuator/health
```

Perform a valid ingestion request:

``` bash
curl -i -X POST \
  http://localhost:8081/api/ingestion/messages \
  -H "Content-Type: application/json" \
  -d '{
    "communicationType": "EMAIL",
    "sender": "alice@example.com",
    "recipients": ["bob@example.com"],
    "subject": "Test message",
    "body": "Testing ingestion.",
    "messageTimestamp": "2026-09-07T18:30:00Z",
    "threadId": "test-thread"
  }'
```

Submit the same request again and verify that the same deduplication key
is returned and no duplicate record is created.

## Current Limitations

The following work is not yet complete:

-   Attachment upload to MinIO/S3
-   Reliable Kafka publication using an outbox pattern
-   Full integration tests
-   Final retention-policy implementation
-   Legal-hold integration
-   Structured error responses
-   API documentation generation
-   Dockerfile for the ingestion service
-   Full containerized end-to-end testing

## Important Reliability Note

The current implementation saves the MongoDB document before publishing
the Kafka event. If MongoDB succeeds and Kafka fails, the message may
exist without its event being published.

The recommended production design is a transactional outbox:

``` text
MongoDB transaction
    |
    +--> Message document
    |
    +--> Outbox event
              |
              v
       Outbox publisher
              |
              v
            Kafka
```

This should be implemented before treating the ingestion service as
production-ready.

## Troubleshooting

### Application returns 404

Use the complete endpoint:

``` text
POST /api/ingestion/messages
```

The base path `/api/ingestion` does not itself define a POST endpoint.

### Kafka serialization error

If the logs contain an error indicating that `MessageIngestedEvent`
cannot be serialized by `StringSerializer`, verify that the producer
value serializer is:

``` yaml
spring:
  kafka:
    producer:
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

### MongoDB contains data in `test`

Verify that the application is configured with:

``` yaml
spring:
  data:
    mongodb:
      database: legal_discovery
```

Then run a clean build and restart the application:

``` bash
mvn clean package
mvn spring-boot:run
```

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

1.  Verify the effective MongoDB database.
2.  Add the `messages` collection mapping explicitly.
3.  Create the unique deduplication index.
4.  Add integration tests.
5.  Implement MinIO attachment storage.
6.  Implement the transactional outbox.
7.  Add the ingestion service to Docker Compose.
