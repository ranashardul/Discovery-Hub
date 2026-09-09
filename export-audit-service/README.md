# Export & Audit Service

The **Export & Audit Service** is a Spring Boot 4 microservice responsible for:

- **Evidence Export** — generating a verifiable ZIP package of a case's evidence
  (messages and attachments) and storing it durably in S3.
- **Audit Trail** — recording significant system actions in an append-only audit
  log that provides chain of custody.

## Architecture

```text
                 Export Request                System Events
                      |                              |
                      v                              v
              +---------------+              +-------------+
              | Export Service|              |Audit Service|
              +-------+-------+              +------+------+
                      |                             |
                Read Evidence                  Audit Events
                      |                             |
                      v                             v
              +---------------+              +-------------+
              |  MongoDB      |              | Audit Store |
              | (messages)    |              | (MongoDB)   |
              +-------+-------+              +-------------+
                      |
                      v
              Export Package (ZIP)
                      |
                      v
                  AWS S3
```

## Asynchronous Export Flow

1. `POST /api/exports` — creates a `QUEUED` job, publishes an
   `export.requested` Kafka event, records an `EXPORT_REQUESTED` audit event
   and returns the job immediately.
2. The `ExportWorker` consumes `export.requested`, reads evidence from the
   shared `messages` collection, builds the package (messages as readable
   text, attachment binaries, `manifest.json` with per-item SHA-256
   checksums) and uploads the ZIP to S3.
3. The job is marked `COMPLETED` (or `FAILED`), an `EXPORT_COMPLETED` Kafka
   event is published, and `EXPORT_COMPLETED` / `EXPORT_FAILED` audit events
   are recorded.
4. `GET /api/exports/{id}/download` returns an expiring presigned S3 URL and
   records an `EXPORT_DOWNLOADED` audit event.
5. `POST /api/exports/{id}/verify` recomputes the package checksum and records
   an `EXPORT_VERIFIED` audit event.
6. `POST /api/exports/{id}/retry` re-queues a failed job.

## Audit Flow

1. Audit events arrive either via the `audit.events` Kafka topic (consumed by
   `AuditEventListener`) or via `POST /api/audit` (synchronous recording).
2. `AuditService.record()` inserts into the `audit_events` MongoDB collection,
   which is append-only — there are no update or delete APIs.
3. The `eventId` is unique, so redelivered Kafka events are idempotent.
4. `GET /api/audit/cases/{caseId}` returns the audit history for a case.
5. `GET /api/audit?targetId=...` returns the history for a specific target.

## API Endpoints

### Export

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/exports` | Request a new export job |
| `GET`  | `/api/exports/{exportId}` | Get export job status |
| `GET`  | `/api/exports?caseId=...` | List exports for a case |
| `POST` | `/api/exports/{exportId}/retry` | Retry a failed export |
| `GET`  | `/api/exports/{exportId}/download` | Get presigned download URL |
| `POST` | `/api/exports/{exportId}/verify` | Verify package checksum |

### Audit

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/audit` | Record an audit event |
| `GET`  | `/api/audit/cases/{caseId}` | Get audit history for a case |
| `GET`  | `/api/audit?targetId=...` | Get audit history for a target |

## Database Collections

| Collection | Owner | Purpose |
|------------|-------|---------|
| `messages` | ingestion-service (read-only here) | Evidence source |
| `export_jobs` | export-audit-service | Export job state |
| `audit_events` | export-audit-service | Append-only audit log |

## Kafka Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `export.requested` | consumed by worker | Triggers async export |
| `export.requested.dlt` | dead-letter | Failed export requests |
| `export.completed` | published by worker | Export finished notification |
| `audit.events` | consumed | External audit events |

## S3 Usage

| Bucket | Purpose |
|--------|---------|
| `discovery-hub-attachments` (source) | Attachment binaries (read-only) |
| `discovery-hub-exports` (export) | Generated export packages |

## Configuration

All settings come from environment variables via the shared `.env` file. No
AWS credentials are hard-coded; the AWS SDK default credentials chain is used.

## Tests

``` bash
mvn test                                          # unit tests
mvn test -Dgroups=integration -Dexcluded.test.groups=  # integration tests
```

Integration tests use Testcontainers (MongoDB, Kafka, MinIO) and require Docker.
