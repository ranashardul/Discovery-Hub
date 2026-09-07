# Case & Hold Service

The **Case & Hold Service** is a microservice responsible for managing legal cases and their associated holds within the S-town platform.

It provides REST APIs for case and hold management, persists case data in PostgreSQL, and publishes domain events through Apache Kafka so that other services can react to case and hold changes asynchronously.


## Responsibilities

### Case Management

* Create cases
* Retrieve cases
* Update case information
* Change case status
* Assign cases
* Maintain case history

### Hold Management

* Place a hold on a case
* Retrieve active holds
* Update hold information
* Release holds
* Maintain hold history

### Event Management

The service publishes Kafka events when important domain changes occur.

Example events:

```text
CASE_CREATED
CASE_UPDATED
CASE_STATUS_CHANGED
HOLD_PLACED
HOLD_RELEASED
```

Other microservices can consume these events without directly accessing the Case & Hold Service database.

---

## Project Structure

```text
case-hold-service/
│
├── src/
│   ├── main/
│   │   ├── java/com/stown/casehold/
│   │   │   ├── CaseHoldServiceApplication.java
│   │   │   │
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── repository/
│   │   │   ├── entity/
│   │   │   ├── dto/
│   │   │   ├── mapper/
│   │   │   ├── kafka/
│   │   │   ├── exception/
│   │   │   └── config/
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │
│   └── test/
│
├── Dockerfile
├── pom.xml
└── README.md
```

---

## Communication

### Synchronous Communication

REST APIs are used for direct operations such as:

```text
Create Case
Get Case
Update Case
Place Hold
Release Hold
Get Active Holds
```

### Asynchronous Communication

Apache Kafka is used for domain events:

```text
Case & Hold Service
        |
        | Kafka
        v
+-----------------------+
| Domain Events         |
+-----------------------+
        |
        +--> Search Service
        |
        +--> Ingestion Service
        |
        +--> Notification Service
        |
        +--> Other Services
```

This allows services to remain loosely coupled.

---

## Database

PostgreSQL stores the authoritative state for cases and holds.

A high-level model is:

```text
+----------------+
|     cases      |
+----------------+
| id             |
| case_number    |
| title          |
| description    |
| status         |
| assigned_to    |
| created_at     |
| updated_at     |
+-------+--------+
        |
        | 1:N
        |
+-------v--------+
|     holds      |
+----------------+
| id             |
| case_id        |
| reason         |
| status         |
| placed_at      |
| released_at    |
| created_by     |
+----------------+
```

Database schema and changes should be managed through **Flyway migrations**.

---

## API

Example endpoints:

```http
POST   /api/v1/cases
GET    /api/v1/cases/{id}
PUT    /api/v1/cases/{id}
PATCH  /api/v1/cases/{id}/status

POST   /api/v1/cases/{id}/holds
GET    /api/v1/cases/{id}/holds
PATCH  /api/v1/holds/{holdId}/release
```

The complete API contract will be documented using OpenAPI / Swagger.

---

## Kafka Events

Example case creation event:

```json
{
  "eventType": "CASE_CREATED",
  "caseId": "12345",
  "timestamp": "2026-09-07T12:00:00Z"
}
```

Example hold event:

```json
{
  "eventType": "HOLD_PLACED",
  "caseId": "12345",
  "holdId": "67890",
  "reason": "Legal preservation",
  "timestamp": "2026-09-07T12:05:00Z"
}
```

Events should be designed to be **versionable, traceable and idempotently consumable**.

---

## Running Locally

### Prerequisites

* Java 21
* Maven
* Docker
* Docker Compose
* Git

### Build

```bash
mvn clean package
```

### Run with Maven

```bash
mvn spring-boot:run
```

### Run with Docker

```bash
docker build -t case-hold-service .
docker run case-hold-service
```

If the project is part of the shared S-town Docker Compose environment:

```bash
docker compose up case-hold-service
```

---


## Design Principles

The service follows these principles:

* **Single Responsibility** — owns case and hold management
* **Database ownership** — other services do not directly access its PostgreSQL database
* **REST for commands/queries** — synchronous operations use REST
* **Kafka for events** — domain changes are propagated asynchronously
* **Layered architecture** — Controller → Service → Repository
* **DTO-based APIs** — entities are not exposed directly
* **Database migrations** — schema changes are version controlled
* **Idempotency** — Kafka consumers should safely handle duplicate events
* **Observability** — logging and health checks should be included

---

## Future Enhancements

* Role-based access control
* Advanced case search
* Case audit trail
* Hold expiration handling
* Event schema versioning
* Distributed tracing
* Metrics and monitoring
* Integration with external legal systems

---

## Service Ownership

**Service:** Case & Hold Service
**Domain:** Case Management / Legal Holds
**Architecture:** Microservice
**Communication:** REST + Apache Kafka
**Database:** PostgreSQL

Architecture:

                         
                         ┌─────────────────────┐
                         │     API Gateway     │
                         └──────────┬──────────┘
                                    │ REST
                                    ▼
                    ┌───────────────────────────┐
                    │     Case & Hold Service   │
                    │        Spring Boot        │
                    │                           │
                    │  ┌─────────────────────┐  │
                    │  │ REST Controllers     │  │
                    │  ├─────────────────────┤  │
                    │  │ Service Layer        │  │
                    │  ├─────────────────────┤  │
                    │  │ Repository Layer     │  │
                    │  └─────────────────────┘  │
                    └──────────┬─────────┬──────┘
                               │         │
                    JPA/SQL    │         │ Kafka
                               ▼         ▼
                    ┌──────────────┐  ┌──────────────┐
                    │ PostgreSQL   │  │    Kafka     │
                    │              │  │              │
                    │ Cases        │  │ Case Events  │
                    │ Holds        │  │ Hold Events  │
                    │ Audit        │  │ Notifications │
                    └──────────────┘  └──────┬───────┘
                                             │
                    ┌────────────────────────┼──────────────────┐
                    │                        │                  │
                    ▼                        ▼                  ▼
             Search Service          Ingestion Service    Notification
             / Other Services                              Service

             
