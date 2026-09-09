# Discovery Hub

A legal-discovery platform built as independently deployable
microservices. Communications are ingested asynchronously, stored
durably, and made searchable within seconds.

``` text
Corpus generator / Angular UI
              |
              v
       Ingestion API  :8081        validate, stage attachments, register request
              |
              v
   Kafka: ingestion.requested
              |
              v
       Ingestion Worker            immutable message ID, durable storage
              |
       +------+------+
       |             |
       v             v
   MongoDB          S3             message data / attachment binaries
       |
       v
   Kafka: message.ingested         published from the outbox
              |
              v
       Search Service  :8082
              |
              v
        Elasticsearch  :9200
              |
              v
   Case & Hold Service :8083      cases, legal holds, communication references
              |
              v
        PostgreSQL  :5432
              |
              v
   Kafka: case-hold.events         CASE_CREATED, HOLD_CREATED, HOLD_RELEASED ...
```

## Repository Layout

  Path                   Contents
  ---------------------- ---------------------------------------------
  `ingestion-service/`   Ingestion API and worker (Java 21, Boot 4)
  `search-service/`      Elasticsearch projection and search API
  `case-hold-service/`   Cases, legal holds, communication references
  `export-audit-service/` Export & Audit Service (Java 21, Boot 4)
  `corpus-generator/`    Python synthetic-corpus generator
  `infrastructure/`      Docker Compose stack
  `docs/`                S3 archival, retention and legal-hold guides
  `.env.example`         Every configuration variable, with placeholders

## Quick Start

``` bash
cp .env.example .env          # fill in real values as needed
ln -s ../.env infrastructure/.env

cd infrastructure
docker compose up -d --build
docker compose ps
```

Every value in `.env` has a working default aimed at the local
containers, so an unedited copy starts a complete local stack.

Verify:

``` bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

## Services and Ports

  Service             Container                   Port
  ------------------- ------------------------- --------
  MongoDB             `stown-mongodb`             `27017`
  Kafka               `stown-kafka`                `9092`
  MinIO API           `stown-minio`                `9000`
  MinIO Console       `stown-minio`                `9001`
  Kafka UI            `stown-kafka-ui`             `8085`
  Elasticsearch       `stown-elasticsearch`        `9200`
  PostgreSQL          `stown-postgres`              `5432`
  Ingestion Service   `stown-ingestion-service`    `8081`
  Search Service      `stown-search-service`       `8082`
  Case & Hold Service `stown-case-hold-service`    `8083`
  Export & Audit      `stown-export-audit-service` `8084`

Kafka advertises `localhost:9092` for host clients and `kafka:19092`
for containers on the compose network. Use container names, never
`localhost`, when connecting from inside a container.

## Generate a Corpus

Start small, then scale up:

``` bash
cd corpus-generator
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

.venv/bin/python generate_corpus.py \
  --messages 20 --custodians 20 --rate 2 --output test-corpus
```

``` bash
.venv/bin/python generate_corpus.py \
  --messages 10000 --custodians 25 --rate 100 --workers 8 \
  --output generated-corpus --seed 42
```

Submissions are idempotent: re-running the same seed creates no
duplicate messages, S3 objects or events.

## Verify

MongoDB:

``` bash
docker exec stown-mongodb mongosh --quiet --eval '
db = db.getSiblingDB("legal_discovery");
print("messages   = " + db.messages.countDocuments());
print("EMAIL      = " + db.messages.countDocuments({communicationType: "EMAIL"}));
print("CHAT       = " + db.messages.countDocuments({communicationType: "CHAT"}));
print("withAtt    = " + db.messages.countDocuments({attachments: {$exists: true, $ne: []}}));
print("custodians = " + db.messages.distinct("sender").length);
print("threads    = " + db.messages.distinct("threadId").length);
print("outboxPend = " + db.messages.countDocuments({outboxStatus: "PENDING"}));
print("failed     = " + db.ingestion_requests.countDocuments({status: "FAILED"}));
'
```

Object storage:

``` bash
docker run --rm --network infrastructure_default \
  -e AWS_ACCESS_KEY_ID=minioadmin -e AWS_SECRET_ACCESS_KEY=minioadmin \
  amazon/aws-cli:latest --endpoint-url http://minio:9000 \
  s3 ls s3://discovery-hub-attachments/ --recursive --summarize
```

Search:

``` bash
curl "http://localhost:8082/api/search?q=budget%20review&size=3"
curl "http://localhost:8082/api/search/stats"
```

Case & Hold:

``` bash
# Create a case
curl -X POST http://localhost:8083/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d '{"caseName":"Acquisition Investigation","description":"Review comms","createdBy":"admin"}'

# Add communications to a case (use the caseId from the response above)
curl -X POST http://localhost:8083/api/v1/cases/<caseId>/communications \
  -H 'Content-Type: application/json' \
  -d '{"communications":[{"communicationId":"msg-1","communicationType":"EMAIL"}],"addedBy":"admin"}'

# Place a hold on the case
curl -X POST http://localhost:8083/api/v1/cases/<caseId>/holds \
  -H 'Content-Type: application/json' \
  -d '{"name":"Preserve key emails","reason":"Legal hold","createdBy":"admin","communications":[{"communicationId":"msg-1","communicationType":"EMAIL"}]}'

# Release the hold
curl -X PATCH http://localhost:8083/api/v1/holds/<holdId>/release \
  -H 'Content-Type: application/json' \
  -d '{"releasedBy":"admin"}'
```

## Configuration

All connection strings come from environment variables and never from
source. `.env` is git-ignored; `.env.example` is the documented
template. To use MongoDB Atlas instead of the local container:

``` text
MONGODB_URI=mongodb+srv://<username>:<password>@<cluster-host>/legal_discovery?retryWrites=true&w=majority
```

For real AWS S3 instead of MinIO, leave `S3_ENDPOINT` empty and supply
`S3_BUCKET`, `AWS_REGION` and credentials through the standard AWS
chain. `AWS_REGION` must match the bucket, otherwise S3 rejects the
request with `PermanentRedirect`.

Attachment binaries are archived in S3 and MongoDB holds only the
metadata and object location. See `docs/s3-archival.md` for the object
layout, the required IAM policy, how to retrieve files (CLI, presigned
URLs, bulk case export), checksum verification and recommended bucket
settings for an archival bucket.

## Tests

``` bash
cd ingestion-service && ./mvnw test
cd search-service   && ./mvnw test
cd search-service   && ./mvnw test -Dgroups=integration -Dexcluded.test.groups=
cd export-audit-service && mvn test
cd export-audit-service && mvn test -Dgroups=integration -Dexcluded.test.groups=
```

Integration tests use Testcontainers and require Docker.

## Retention and Legal Holds

Messages carry a retention period resolved per communication type at
ingestion. A scheduled process deletes those past retention from MongoDB and
S3, **except any message under legal hold**, and records every run.

``` bash
# retention countdown and hold state for one message
curl "http://localhost:8081/api/ingestion/messages/<messageId>/retention"

# what the last runs deleted and skipped
curl "http://localhost:8081/api/ingestion/disposition/runs?limit=5"

# deleting a held message is refused with 409
curl -X DELETE "http://localhost:8081/api/ingestion/messages/<messageId>"
```

Disposition is **off by default** and a period shorter than 24 hours refuses
to start unless explicitly allowed, because a demo setting reaching real data
would destroy it. Holds are placed through the case-hold service and reach
ingestion as `case-hold.events`.

See `docs/retention-and-holds.md` for the configuration, the one-minute demo
and the verification commands.

## Further Reading

-   `docs/retention-and-holds.md`
-   `docs/s3-archival.md`
-   `ingestion-service/README.md` — architecture, API reference, code guide
-   `ingestion-service/ingestion-service-run.md` — run book
-   `search-service/README.md`
-   `Case-hold-service-readme.md`
-   `export-audit-service/README.md`
-   `corpus-generator/README.md`
