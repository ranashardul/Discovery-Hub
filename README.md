## DiscoveryHub — Export & Audit Service

## 1. Overview

The **Export & Audit Service** is responsible for two important parts of DiscoveryHub:

- **Evidence Export:** Creates a reproducible and verifiable export package for a case's evidence or a legal hold scope.
- **Audit Trail:** Records significant system actions in an append-only audit log to provide chain of custody.

DiscoveryHub is an e-discovery and legal-hold platform that allows investigators/compliance officers to search communications, place legal holds, export evidence, and review the history of actions.

## 2. Responsibilities

### Export Service

The Export Service should:

1. Accept an export request for:
   - Case evidence items, or
   - The full scope of a legal hold.
2. Create an asynchronous export job.
3. Return a **job ID** immediately.
4. Track job status:
   - `QUEUED`
   - `RUNNING`
   - `COMPLETED`
   - `FAILED`
5. Create an export package containing:
   - Messages in a readable format
   - All related attachments
   - A manifest
6. Generate:
   - A checksum for every exported item
   - A package-level checksum
7. Store completed export packages durably.
8. Provide an expiring download link for completed exports.
9. Allow failed export jobs to be retried without creating corrupt or duplicate packages.
10. Allow the exported package to be verified by recomputing checksums from the manifest.

### Audit Service

The Audit Service should:

1. Record significant system actions in an **append-only audit log**.
2. Capture events such as:
   - Case created/updated/transitioned
   - Custodian added
   - Evidence added/removed
   - Hold placed/released
   - Search executed
   - Export requested
   - Export downloaded
   - Disposition runs
3. Store audit details including:
   - Timestamp
   - Actor
   - Action
   - Target entity
   - Before/after details where applicable
4. Prevent audit entries from being updated or deleted through APIs.
5. Support viewing/filtering audit entries by case and across the system.

## 3. High-Level Architecture
```text


                         DiscoveryHub
                              |
                +-------------+-------------+
                |                           |
          Export Request                System Events
                |                           |
                v                           v
        +---------------+             +-------------+
        | Export Service|             |Audit Service|
        +-------+-------+             +------+------+
                |                            |
          Read Evidence                  Audit Events
                |                            |
                v                            v
        +---------------+             +-------------+
        | Data Services |             | Audit Store |
        +-------+-------+             +-------------+
                |
                v
           Export Package
                |
                v
             AWS S3
```

## Asynchronous Export Flow
```text
Investigator
     |
     | POST /exports
     v
Export Service
     |
     | Create job
     v
Job ID returned immediately
     |
     | Background processing
     v
Read evidence + attachments
     |
     v
Generate manifest + checksums
     |
     v
Create export package
     |
     v
Store package in S3
     |
     v
Mark job COMPLETED
     |
     v
Generate expiring download link
```


## Audit Flow
```text

System Action
     |
     v
Application / Service
     |
     | Audit event
     v
Kafka / Messaging
     |
     v
Audit Service
     |
     v
Append-only Audit Store
```
