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

## Database

PostgreSQL stores cases, holds, communication references and the outbox.
Schema is managed by Flyway migrations in `src/main/resources/db/migration/`.

``` text
cases                  holds                  event_outbox
case_communications    hold_communications
```

## Configuration

All settings come from environment variables:

| Variable                    | Default                          | Description                |
|----------------------------|----------------------------------|----------------------------|
| `CASE_HOLD_PORT`           | `8083`                           | Server port                |
| `POSTGRES_URI`             | `jdbc:postgresql://localhost:5432/case_hold` | JDBC URL       |
| `POSTGRES_USER`            | `casehold`                       | DB user                    |
| `POSTGRES_PASSWORD`        | `casehold`                       | DB password                |
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

# Integration tests (Testcontainers: PostgreSQL + Kafka)
./mvnw test -Dgroups=integration -Dexcluded.test.groups=
```

## Design Principles

- **Single Responsibility** — owns case and hold management only
- **No ingestion or search** — those belong to Ingestion and Search services
- **No message duplication** — stores only references to communications
- **Transactional outbox** — events are never lost when Kafka is down
- **Idempotent operations** — re-adding communications or re-releasing holds is safe
- **Database ownership** — no other service accesses its PostgreSQL directly
