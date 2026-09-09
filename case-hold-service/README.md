# Case & Hold Service

The **Case & Hold Service** manages legal/business investigations (cases),
associates discovered communications with those cases, places preservation
holds on relevant communications, releases those holds, and publishes every
change through Kafka so other services can react asynchronously.

It answers one question:

> **Which business communications belong to a legal/business case, and which
> of those records must be preserved because they are on hold?**

## Architecture

``` text
              ┌─────────────────────────────────────────┐
              │           Case & Hold Service :8083     │
              │                                         │
              │  REST Controllers  →  Service Layer      │
              │                      →  Repository Layer │
              │                      →  Transactional    │
              │                         Outbox          │
              └───────────┬───────────────────┬─────────┘
                          │ JPA               │ Kafka
                          ▼                   ▼
                    PostgreSQL  :5432    case-hold.events
                                              │
                    ┌────────────────────────┼──────────┐
                    ▼                        ▼          ▼
             Search Service          Ingestion Service  Other
```

The service owns its PostgreSQL database exclusively. Other services never
access it directly; they consume domain events from Kafka.

## Endpoints

``` http
POST   /api/v1/cases                         Create a case
GET    /api/v1/cases                          List cases (optional ?status=)
GET    /api/v1/cases/{caseId}                 Get a case
PATCH  /api/v1/cases/{caseId}                 Update a case (name/description/status)

POST   /api/v1/cases/{caseId}/communications  Associate communications with a case
GET    /api/v1/cases/{caseId}/communications  List communications for a case

POST   /api/v1/cases/{caseId}/holds           Create a hold on a case
GET    /api/v1/cases/{caseId}/holds           List holds for a case
GET    /api/v1/holds/{holdId}                 Get a hold
GET    /api/v1/holds/{holdId}/communications  List communications on a hold
PATCH  /api/v1/holds/{holdId}/release         Release a hold
```

## Hold Scopes

A hold can be **communication-level** (an explicit list of communication IDs)
or **criteria-based** (a preservation rule with participants, communication
types and a date range). The criteria are carried in the `HOLD_CREATED` event
so the service that owns the message data can match and preserve the records.

## Kafka Events

All events are published to `case-hold.events` via a transactional outbox:

``` text
CASE_CREATED
CASE_UPDATED
COMMUNICATION_ADDED_TO_CASE
HOLD_CREATED
HOLD_RELEASED
```

Each event carries an `eventType` field in the payload (and an `event_type`
message header) so consumers can route without type headers from the
producer. The `eventId` (UUID) makes consumption idempotent.

## Databases

**PostgreSQL** is the authoritative store for cases, holds, communication
references and the event outbox. Schema is managed by Flyway migrations in
`src/main/resources/db/migration/`.

``` text
cases                  holds                  event_outbox
case_communications    hold_communications
```

**MongoDB** is read only. The service resolves each stored
`communication_id` against the `messages` collection owned by
ingestion-service so the API can return real message metadata — sender,
subject, timestamp, attachment count — instead of a bare identifier. It never
writes to MongoDB and `auto-index-creation` is disabled, because it owns no
collection there.

``` text
PostgreSQL                          MongoDB (read only)
case_communications                 messages
  communication_id  ───────────────►  _id
                                      sender, subject, messageTimestamp
                                      holdCount, dispositionStatus
```

Set `MONGODB_URI` to a `mongodb+srv://` string to read from an Atlas cluster.

### Resolved and unresolved references

A reference that matches no message is still returned, with
`message.resolved = false` and the rest of the detail null, and counted in
`unresolvedCount`. Case and hold records are the legal artefacts and must stay
readable even when a supplied identifier is wrong or the message store is
unreachable, so a lookup failure degrades rather than failing the request.

### Recorded versus enforced

`GET /api/v1/holds/{holdId}/communications` returns `enforcedCount`: how many
of the covered messages actually carry `holdCount > 0` in the message store.
This service records a hold; ingestion-service enforces it by consuming
`case-hold.events` and projecting `holdIds` onto each message. Comparing
`total` with `enforcedCount` shows whether the projection has caught up.

## Configuration

All settings come from environment variables:

| Variable                    | Default                          | Description                |
|----------------------------|----------------------------------|----------------------------|
| `CASE_HOLD_PORT`           | `8083`                           | Server port                |
| `POSTGRES_URI`             | `jdbc:postgresql://localhost:5432/case_hold` | JDBC URL       |
| `POSTGRES_USER`            | `casehold`                       | DB user                    |
| `POSTGRES_PASSWORD`        | `casehold`                       | DB password                |
| `MONGODB_URI`              | `mongodb://localhost:27017/legal_discovery` | Read-only message store; accepts `mongodb+srv://` for Atlas |
| `KAFKA_BOOTSTRAP_SERVERS`  | `localhost:9092`                 | Kafka brokers              |
| `CASE_HOLD_TOPIC`          | `case-hold.events`               | Kafka event topic          |
| `LOG_LEVEL`                | `INFO`                           | Log level for `com.stown`  |

## Build & Run

``` bash
# Build
./mvnw clean package

# Run (requires PostgreSQL and Kafka)
./mvnw spring-boot:run

# Tests (unit + context load)
./mvnw test

# Integration tests (Testcontainers: PostgreSQL + MongoDB + Kafka)
./mvnw test -Dgroups=integration -Dexcluded.test.groups=
```

Integration tests are hermetic: they run against throwaway containers and
never touch a shared cluster, so no Atlas credentials are needed.

## Design Principles

- **Single Responsibility** — owns case and hold management only
- **No ingestion or search** — those belong to Ingestion and Search services
- **No message duplication** — stores only references to communications;
  message metadata is read on demand and never copied into PostgreSQL, and the
  message body is deliberately not mapped at all
- **Read-only outside its own store** — writes only to PostgreSQL; MongoDB
  access is read-only, matching how search-service and export-audit-service
  read the same collection
- **Transactional outbox** — events are never lost when Kafka is down
- **Idempotent operations** — re-adding communications or re-releasing holds is safe
- **Database ownership** — no other service accesses its PostgreSQL directly
