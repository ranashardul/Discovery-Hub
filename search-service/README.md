# search-service

Search side of the Discovery Hub legal-discovery platform.

## Responsibilities

- Consume the `message.ingested` Kafka event published by **ingestion-service**.
- Read the corresponding message from MongoDB (`legal_discovery.messages`, read-only).
- Index it into Elasticsearch (index `messages`, document id == `messageId`).
- Expose a read-only search API over the indexed corpus.
- Guarantee that every ingested message is searchable within **30 seconds**
  (event-driven indexing plus a reconciliation safety net).

Port: **8082**

## Architecture

```
ingestion-service ──(message.ingested)──▶ Kafka ──▶ search-service listener
                                                        │
                              MongoDB legal_discovery.messages (read)
                                                        │
                                                        ▼
                                       Elasticsearch index "messages"
                                                        │
                                                        ▼
                                            GET /api/search (REST)
```

## Event contract consumed

Topic `message.ingested`, consumer group `search-service`.

```json
{
  "eventId": "6b8b4567-651f-4f2c-a4b0-9c0a1a5e2c11",
  "messageId": "11111111-1111-1111-1111-111111111111",
  "deduplicationKey": "<64 hex characters>",
  "occurredAt": "2026-09-08T03:00:00Z"
}
```

The producer serialises `com.stown.ingestion.messaging.MessageIngestedEvent`
with a Kafka type header. This service deserialises into
`com.stown.search.messaging.MessageIngestedEvent`, so type-header mapping is
disabled and a default value type is configured instead:

```yaml
spring:
  kafka:
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.JacksonJsonDeserializer
      properties:
        spring.json.use.type.headers: false
        spring.json.value.default.type: com.stown.search.messaging.MessageIngestedEvent
        spring.json.trusted.packages: "com.stown.search"
```

Unknown properties are ignored so the event schema can evolve.

## Elasticsearch mapping

Created idempotently at startup (and lazily before the first write) if the
index does not already exist.

| Field                  | Type                              | Purpose                    |
|------------------------|-----------------------------------|----------------------------|
| `messageId`            | keyword                           | document id, exact lookup  |
| `deduplicationKey`     | keyword                           | exact lookup               |
| `externalMessageId`    | keyword                           | exact lookup               |
| `communicationType`    | keyword                           | filter (`EMAIL` / `CHAT`)  |
| `threadId`             | keyword                           | filter                     |
| `sender`               | text + `sender.keyword`           | full text + exact filter   |
| `recipients`           | text + `recipients.keyword`       | full text + exact filter   |
| `subject`              | text + `subject.keyword`          | full text (boosted x2)     |
| `body`                 | text                              | full text                  |
| `attachmentFilenames`  | text + `attachmentFilenames.keyword` | full text + exact filter |
| `messageTimestamp`     | date                              | sorting / range            |
| `indexedAt`            | date                              | observability              |
| `attachmentCount`      | integer                           | display                    |
| `holdCount`            | integer                           | legal-hold state           |
| `dispositionStatus`    | keyword                           | disposition state          |

Indexing uses `messageId` as the Elasticsearch document id, so replays and
reconciliation overwrite rather than duplicate.

`holdCount` and `dispositionStatus` are owned by the case/hold service and are
projected read-only, so a reviewer can see whether a result is under legal hold
without a second round trip.

> **Mapping change.** `holdCount` and `dispositionStatus` were added after the
> first release. An index created before that has no mapping for them, so
> Elasticsearch infers one on first write and `dispositionStatus` becomes
> `text` — which an exact-match filter cannot use. Drop the index once and let
> the reconciliation job back-fill it:
>
> ```bash
> curl -X DELETE "http://localhost:9200/messages"
> ```
>
> Nothing is lost: MongoDB is the source of truth and
> `SEARCH_RECONCILE_BACKFILL_ENABLED` rebuilds the index within
> `SEARCH_RECONCILE_INTERVAL_MS`.

## Endpoints

### Full-text search

```bash
curl "http://localhost:8082/api/search?q=merger%20agreement&communicationType=EMAIL&sender=alice@example.com&threadId=thread-1&from=0&size=20"
```

```json
{
  "query": "merger agreement",
  "total": 123,
  "from": 0,
  "size": 20,
  "tookMillis": 12,
  "results": [
    {
      "messageId": "11111111-1111-1111-1111-111111111111",
      "score": 4.21,
      "communicationType": "EMAIL",
      "sender": "alice@example.com",
      "recipients": ["bob@example.com"],
      "subject": "Project Falcon merger agreement",
      "snippet": "Attached is the signed <em>merger</em> <em>agreement</em> ...",
      "threadId": "thread-falcon",
      "messageTimestamp": "2026-09-08T03:00:00Z",
      "attachmentCount": 1,
      "onHold": true,
      "dispositionStatus": "RETAINED"
    }
  ]
}
```

- `q` is required and must not be blank (`400` otherwise).
- `size` defaults to 20 and is capped at 100 (`SEARCH_MAX_PAGE_SIZE`).
- `snippet` is an Elasticsearch highlight over `subject`/`body`, falling back
  to a truncated body when there is no highlight fragment.

### Fetch an indexed document

```bash
curl "http://localhost:8082/api/search/messages/11111111-1111-1111-1111-111111111111"
```

Returns the indexed document, or `404` if the message is not in the index.

### Index statistics

```bash
curl "http://localhost:8082/api/search/stats"
```

```json
{ "indexedCount": 4211, "index": "messages", "pendingFailures": 0 }
```

### Actuator

```bash
curl "http://localhost:8082/actuator/health"
curl "http://localhost:8082/actuator/info"
```

### Error shape

Mirrors ingestion-service:

```json
{
  "timestamp": "2026-09-08T03:28:11.461746Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request validation failed",
  "path": "/api/search",
  "fieldErrors": [
    { "field": "q", "message": "Query parameter 'q' is required and must not be blank" }
  ]
}
```

## Environment variables

| Variable                        | Default                                          | Description                                    |
|---------------------------------|--------------------------------------------------|------------------------------------------------|
| `SEARCH_PORT`                   | `8082`                                           | HTTP port                                      |
| `MONGODB_URI`                   | `mongodb://localhost:27017/legal_discovery`      | Mongo connection URI (`spring.mongodb.uri`)    |
| `KAFKA_BOOTSTRAP_SERVERS`       | `localhost:9092`                                 | Kafka bootstrap servers                        |
| `ELASTICSEARCH_URIS`            | `http://localhost:9200`                          | Elasticsearch endpoint(s)                      |
| `ELASTICSEARCH_USERNAME`        | *(empty)*                                        | Elasticsearch basic-auth user                  |
| `ELASTICSEARCH_PASSWORD`        | *(empty)*                                        | Elasticsearch basic-auth password              |
| `SEARCH_INDEX`                  | `messages`                                       | Index name                                     |
| `SEARCH_RECONCILE_INTERVAL_MS`  | `60000`                                          | Reconciliation period                          |

Additional tunables (all optional): `SEARCH_RECONCILE_BATCH_SIZE` (100),
`SEARCH_RECONCILE_BACKFILL_ENABLED` (true), `SEARCH_MAX_PAGE_SIZE` (100),
`SEARCH_SNIPPET_LENGTH` (240), `SEARCH_TOPIC`, `SEARCH_DEAD_LETTER_TOPIC`,
`SEARCH_RETRY_ATTEMPTS` (3), `SEARCH_RETRY_INITIAL_INTERVAL_MS` (1000),
`SEARCH_RETRY_MULTIPLIER` (2.0), `SEARCH_RETRY_MAX_INTERVAL_MS` (10000).

Configuration lives in the single project-level `.env` at the repository
root; copy it from `../.env.example` and fill it in. No credential is
ever hardcoded.

## Reliability behaviour

- **Retry**: listener failures are retried with exponential backoff
  (3 attempts, 1s initial, x2 multiplier, capped at 10s).
- **Dead letter**: after the retries are exhausted the record is republished to
  `message.ingested.dlt` by a `DeadLetterPublishingRecoverer`.
- **Failure ledger**: every indexing failure is upserted into the MongoDB
  collection `search_index_failures`
  (`_id` = messageId, `messageId`, `eventId`, `attempts`, `lastError`,
  `firstFailedAt`, `lastFailedAt`, `resolved`). A later success flips
  `resolved` to `true`.
- **Reconciliation**: a scheduled job (default every 60s) retries unresolved
  failures and back-fills up to 100 messages that exist in MongoDB but are
  missing from Elasticsearch.
- **Idempotency**: the Elasticsearch document id is the `messageId`, so
  replays, retries and back-fills overwrite in place.
- **Structured logging**: indexing logs `messageId`, `eventId`, `index` and
  `elapsedMs`; failures log the same context plus the reason.

## Running locally

```bash
cd search-service
./mvnw -DskipTests package
java -jar target/search-0.0.1-SNAPSHOT.jar
```

or with explicit configuration:

```bash
MONGODB_URI=mongodb://localhost:27017/legal_discovery \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
ELASTICSEARCH_URIS=http://localhost:9200 \
./mvnw spring-boot:run
```

## Running in Docker

```bash
cd search-service
docker build -t discovery-hub/search-service .
docker run --rm -p 8082:8082 \
  -e MONGODB_URI=mongodb://mongodb:27017/legal_discovery \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e ELASTICSEARCH_URIS=http://elasticsearch:9200 \
  discovery-hub/search-service
```

## Tests

```bash
./mvnw test                      # unit tests only (no containers)
./mvnw test -Dgroups=integration -Dexcluded.test.groups=   # Testcontainers end-to-end test
```

The Testcontainers test (`SearchIntegrationTest`) is tagged `integration` and
excluded from the default surefire run through the `excluded.test.groups`
property, which the command above clears. It starts MongoDB, Kafka and
Elasticsearch as singleton containers (started in a static initialiser, no
`@Container` annotation) so the cached Spring context keeps working across test
classes, and proves that publishing `message.ingested` for a message present in
MongoDB produces an Elasticsearch document with id == `messageId` that is
returned by `GET /api/search`.

## Verifying the <30 second searchability target

1. Ingest a message and note the returned `messageId` and the wall-clock time:

   ```bash
   START=$(date +%s)
   MESSAGE_ID=$(curl -s -X POST http://localhost:8081/api/messages \
     -H 'Content-Type: application/json' \
     -d '{ ... }' | jq -r .messageId)
   ```

2. Poll the search service until the document appears:

   ```bash
   until curl -sf "http://localhost:8082/api/search/messages/$MESSAGE_ID" > /dev/null; do
     sleep 1
   done
   echo "searchable after $(( $(date +%s) - START ))s"
   ```

3. Confirm it is reachable through full-text search too:

   ```bash
   curl "http://localhost:8082/api/search?q=<a+word+from+the+body>" | jq '.results[].messageId'
   ```

The elapsed value should be a small number of seconds: indexing happens on the
Kafka event and Elasticsearch's default 1s refresh interval makes the document
visible shortly after. `GET /api/search/stats` shows `pendingFailures`, which
must be `0` for a healthy pipeline; anything stuck there is retried by the
reconciliation job within `SEARCH_RECONCILE_INTERVAL_MS`.
