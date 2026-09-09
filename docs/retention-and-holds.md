# Retention, Disposition and Legal Holds

Manual commands for the ingestion service's retention behaviour: how to
configure it, how to run the one-minute demo, how to verify what happened, and
what other services need from it.

Owned by the ingestion service. Holds themselves are owned by the case-hold
service; this service only consumes them.

## How It Works

``` text
case-hold service                    ingestion service
      |                                     |
      | HOLD_CREATED / HOLD_RELEASED        |
      +---- case-hold.events -------------->|  holdIds set on each message
                                            |  holdCount + dispositionStatus derived
                                            |
                                   DispositionService (scheduled)
                                            |
                          retentionUntil <= now AND holdCount = 0
                                            |
                        +-------------------+-------------------+
                        |                   |                   |
                        v                   v                   v
                  MongoDB delete       S3 objects         message.disposed
                  (conditional)          purged            published
                                            |
                                            v
                                   disposition_runs +
                                   disposition_audit
```

Retention is stored per message at ingestion time, resolved from the period
configured for its communication type. A message is deleted only when
retention has expired **and** no hold covers it. A held message is skipped and
recorded as skipped, on every run, until the last hold is released.

## Configuration

All values live in the project-level `.env`.

  Variable | Default | Meaning
  ---------|---------|--------
  `DISPOSITION_ENABLED` | `false` | Master switch. Off means nothing is ever deleted.
  `DISPOSITION_DRY_RUN` | `false` | Report what would be deleted, delete nothing.
  `RETENTION_EMAIL` | `2555d` | Retention for `EMAIL` (7 years).
  `RETENTION_CHAT` | `1095d` | Retention for `CHAT` (3 years).
  `RETENTION_DEFAULT` | `2555d` | Applied to any other communication type.
  `RETENTION_MIN_PERIOD` | `24h` | Floor; a shorter period refuses to start.
  `ALLOW_SHORT_RETENTION` | `false` | Permits periods below the floor. Demo only.
  `DISPOSITION_INTERVAL_MS` | `60000` | Delay between runs.
  `DISPOSITION_BATCH_SIZE` | `200` | Candidates examined per run.
  `DISPOSITION_MAX_DELETES` | `500` | Ceiling on deletions per run.
  `PURGE_SWEEP_INTERVAL_MS` | `60000` | Retry interval for incomplete purges.
  `CASE_HOLD_TOPIC` | `case-hold.events` | Topic published by the case-hold service.

Periods are Spring `Duration` values, so `30s`, `1m`, `24h`, `1095d` all
parse. `1m` means one **minute**.

### Safety guards

The service **refuses to start** if any period is below `RETENTION_MIN_PERIOD`
unless `ALLOW_SHORT_RETENTION=true`:

``` text
Retention period for EMAIL is PT1M, below the configured floor of PT24H.
Set app.retention.allow-short-retention=true to permit it.
```

That is deliberate. A one-minute retention reaching an environment with real
data would delete it on the next run.

The effective policy is logged at startup, so a demo setting is never silent:

``` bash
docker logs stown-ingestion-service | grep "Retention policy"
```

## The One-Minute Demo

Deletes anything ingested a minute later. Use a handful of messages, never a
full corpus, and revert afterwards.

### 1. Configure

In `.env`:

``` text
DISPOSITION_ENABLED=true
ALLOW_SHORT_RETENTION=true
RETENTION_EMAIL=1m
RETENTION_CHAT=1m
DISPOSITION_INTERVAL_MS=10000
```

``` bash
cd infrastructure
docker compose up -d ingestion-service
docker logs stown-ingestion-service | grep "Retention policy"
```

### 2. Ingest two messages

``` bash
for id in demo-keep demo-delete; do
  curl -s -X POST http://localhost:8081/api/ingestion/messages \
    -H "Content-Type: application/json" \
    -d "{
      \"communicationType\": \"EMAIL\",
      \"sender\": \"alice.sharma@example-bank.test\",
      \"recipients\": [\"bob.patel@example-bank.test\"],
      \"subject\": \"Retention demo $id\",
      \"body\": \"Ingested for the retention demonstration.\",
      \"messageTimestamp\": \"2026-09-08T03:00:00Z\",
      \"externalMessageId\": \"$id\"
    }"; echo
done
```

Resolve the assigned message ids:

``` bash
KEEP=$(curl -s "http://localhost:8081/api/ingestion/requests?externalMessageId=demo-keep" \
  | sed -n 's/.*"messageId":"\([^"]*\)".*/\1/p')
DELETE=$(curl -s "http://localhost:8081/api/ingestion/requests?externalMessageId=demo-delete" \
  | sed -n 's/.*"messageId":"\([^"]*\)".*/\1/p')
echo "keep=$KEEP delete=$DELETE"
```

### 3. Place a hold on one of them

Through the **case-hold service**, which owns holds:

``` bash
CASE_ID=$(curl -s -X POST http://localhost:8083/api/v1/cases \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Retention demo",
    "description": "Demonstrates that a hold blocks disposition",
    "matterType": "INVESTIGATION",
    "owner": "investigator"
  }' | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -s -X POST "http://localhost:8083/api/v1/cases/$CASE_ID/holds" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Demo hold\",
    \"scope\": \"COMMUNICATION\",
    \"communicationIds\": [\"$KEEP\"],
    \"createdBy\": \"investigator\"
  }"; echo
```

Confirm the hold reached this service:

``` bash
curl -s "http://localhost:8081/api/ingestion/messages/$KEEP/retention"
```

Expect `"held": true`, `"holdCount": 1`, `"dispositionStatus": "ON_HOLD"` and
the hold id listed.

To place a hold **without** the case-hold service, publish the event directly:

``` bash
docker exec -i stown-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:19092 --topic case-hold.events <<EOF
{"eventType":"HOLD_CREATED","eventId":"manual-1","holdId":"hold-manual-1","caseId":"case-manual","status":"ACTIVE","scope":"COMMUNICATION","communicationIds":["$KEEP"],"occurredAt":"2026-09-08T10:00:00Z"}
EOF
```

### 4. Watch the countdown

``` bash
watch -n 5 "curl -s http://localhost:8081/api/ingestion/messages/$DELETE/retention"
```

`secondsUntilExpiry` falls to zero, then within one `DISPOSITION_INTERVAL_MS`
the message is gone.

### 5. Confirm the outcome

``` bash
# The unheld message is gone, the held one survives
curl -s -o /dev/null -w "unheld: %{http_code}\n" \
  "http://localhost:8081/api/ingestion/messages/$DELETE/retention"
curl -s -o /dev/null -w "held  : %{http_code}\n" \
  "http://localhost:8081/api/ingestion/messages/$KEEP/retention"
```

`404` for the deleted one, `200` for the held one.

``` bash
# What the run recorded (FR-5.3)
curl -s "http://localhost:8081/api/ingestion/disposition/runs?limit=3"
```

Expect `"deleted": 1` and `"skippedOnHold": 1`.

### 6. Prove deletion of a held message is refused (FR-4.6)

``` bash
curl -s -w "\nHTTP %{http_code}\n" -X DELETE \
  "http://localhost:8081/api/ingestion/messages/$KEEP"
```

``` json
{
  "status": 409,
  "error": "Conflict",
  "message": "Message ... is under legal hold (holdCount=1); deletion refused; holds: hold-..."
}
```

The message is still there afterwards.

### 7. Release the hold and watch it dispose

``` bash
HOLD_ID=$(curl -s "http://localhost:8083/api/v1/cases/$CASE_ID/holds" \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)

curl -s -X PATCH "http://localhost:8083/api/v1/holds/$HOLD_ID/release" \
  -H "Content-Type: application/json" \
  -d '{"releasedBy": "investigator"}'; echo

curl -s "http://localhost:8081/api/ingestion/messages/$KEEP/retention"
```

`holdCount` returns to `0` and `dispositionStatus` to `ACTIVE`; the next run
deletes it.

### 8. Revert

``` text
DISPOSITION_ENABLED=false
ALLOW_SHORT_RETENTION=false
RETENTION_EMAIL=2555d
RETENTION_CHAT=1095d
DISPOSITION_INTERVAL_MS=60000
```

``` bash
docker compose up -d ingestion-service
```

## Rehearsing Safely: Dry Run

`DISPOSITION_DRY_RUN=true` logs and records candidates without deleting
anything. Use it before changing a period on data that matters.

``` bash
docker logs -f stown-ingestion-service | grep "dry-run"
```

``` text
[dry-run] would dispose messageId=... retentionUntil=... attachments=1
```

The run record is still written with `"dryRun": true`, so the impact of a
policy change is measurable before it is applied.

## Verification Commands

### MongoDB

``` bash
set -a; . ./.env; set +a
docker run --rm mongo:7 mongosh "$MONGODB_URI" --quiet --eval '
print("messages          = " + db.messages.countDocuments());
print("held              = " + db.messages.countDocuments({holdCount: {$gt: 0}}));
print("retention expired = " + db.messages.countDocuments({retentionUntil: {$lte: new Date()}}));
print("disposed requests = " + db.ingestion_requests.countDocuments({status: "DISPOSED"}));
print("audit DELETED     = " + db.disposition_audit.countDocuments({outcome: "DELETED"}));
print("audit SKIPPED     = " + db.disposition_audit.countDocuments({outcome: "SKIPPED_ON_HOLD"}));
print("audit PENDING     = " + db.disposition_audit.countDocuments({outcome: "PENDING"}));
'
```

`audit PENDING` should be zero at rest. A non-zero value means a purge did not
finish; the sweeper retries it.

Recent disposition decisions:

``` bash
docker run --rm mongo:7 mongosh "$MONGODB_URI" --quiet --eval '
db.disposition_audit.find({}, {messageId:1, outcome:1, reason:1, decidedAt:1})
  .sort({decidedAt:-1}).limit(10).forEach(printjson)
'
```

Holds as this service sees them:

``` bash
docker run --rm mongo:7 mongosh "$MONGODB_URI" --quiet --eval '
db.hold_state.find({}, {status:1, scope:1, messagesAffected:1, lastEventAt:1}).forEach(printjson)
'
```

### S3

Objects for a disposed message must be gone:

``` bash
aws s3 ls "s3://discoveryhub-export-bucket-2026/messages/$DELETE/" --recursive
```

Empty output is the expected result. See `docs/s3-archival.md` for retrieval.

### Kafka

``` bash
docker exec stown-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 \
  --topic message.disposed --from-beginning --timeout-ms 5000
```

## Event Contract: `message.disposed`

Published after a message has been removed from MongoDB and its attachments
purged. Key is the `messageId`.

``` json
{
  "eventId": "3f7c...",
  "messageId": "24b4d2ba-6d61-4531-b020-2f190e158cdf",
  "externalMessageId": "corpus-000042",
  "communicationType": "EMAIL",
  "reason": "RETENTION",
  "attachmentsPurged": 1,
  "retentionUntil": "2026-09-08T10:01:00Z",
  "disposedAt": "2026-09-08T10:01:07Z"
}
```

`reason` is `RETENTION` for the scheduled job or `MANUAL` for the delete API.
Delivery is at-least-once, so consumers must be idempotent; deleting an
already absent record is a no-op.

### For the search service

**Elasticsearch keeps the `subject` and `body` of a disposed message until its
document is deleted.** MongoDB and S3 no longer hold the data, but it stays
searchable, which defeats the point of disposition.

`MessageIndexClient` has no `delete` operation today (recorded as a known open
item in `AGENTS.md`). Consuming `message.disposed` and deleting the document
by `messageId` closes it. Two related notes:

- back-fill reads MongoDB, so a disposed message is never re-indexed; the
  stale document simply lingers.
- a `search_index_failures` entry for a disposed message can never resolve,
  because the message no longer exists in MongoDB. This event is also the
  signal to purge that ledger entry.

Until that consumer exists, expect a disposed message to still appear in
`/api/search`. Compare the counts to see the drift:

``` bash
curl -s localhost:9200/messages/_count
docker run --rm mongo:7 mongosh "$MONGODB_URI" --quiet \
  --eval 'print(db.messages.countDocuments())'
```

### For the export and audit service

Every disposition is also recorded locally in `disposition_audit` (per message)
and `disposition_runs` (per run), which covers FR-5.3. Consuming
`message.disposed` gives the audit trail the chain-of-custody entry required by
FR-7.1 without reading this service's database.

## Why Not a TTL Index

The obvious shortcut is a MongoDB TTL index:

``` javascript
// Do not do this
db.messages.createIndex({ retentionUntil: 1 }, { expireAfterSeconds: 0 })
```

TTL deletion has no conditional predicate, so it would:

- delete messages under legal hold, breaking FR-4.2 and destroying evidence
- leave every S3 attachment orphaned
- write no audit record, so nothing could prove what was disposed
- never publish `message.disposed`, leaving the content searchable

Retention has to be enforced in the application, where the hold check and the
delete can be a single atomic operation.

## Design Notes

**Holds are a set, not a counter.** Each message carries `holdIds`, and
`holdCount` and `dispositionStatus` are derived from it inside the same atomic
update. Hold events are delivered at least once, so an increment would
double-count on redelivery, and a release event names only the hold, not the
messages. Overlapping holds are correct by construction: releasing one hold
only clears protection when no other hold id remains.

**The audit record is written before the delete.** It carries the object keys,
so a crash between the MongoDB delete and the S3 purge cannot strand the
binaries; `PurgeSweeper` finishes the job from the record.

**The delete is conditional.** `findAndRemove` is filtered on
`holdCount = 0`, so a hold arriving between reading a candidate and deleting it
causes the delete to match nothing rather than removing held evidence.

**A disposed request is marked `DISPOSED`, not removed.** Re-submitting the
same content reports the disposed status instead of silently deduplicating
against a message that no longer exists. To load a fresh corpus, use a new
`--external-id-prefix` in the generator.
