# Ingestion Service — Run Book

Every command is on its own line. Run them one at a time, top to bottom.
Nothing is chained with `&&`, and no command is nested inside another.

Where a command prints an id you need later, the next step shows where to
paste it.

Sections:

1.  [Start the service](#1-start-the-service)
2.  [Check it is working](#2-check-it-is-working)
3.  [Ingest one message](#3-ingest-one-message)
4.  [Find the message id](#4-find-the-message-id)
5.  [Ingest a message with an attachment](#5-ingest-a-message-with-an-attachment)
6.  [Ingest 10,000 messages in bulk](#6-ingest-10000-messages-in-bulk)
7.  [Delete a message in one minute](#7-delete-a-message-in-one-minute)
8.  [Show that a legal hold blocks deletion](#8-show-that-a-legal-hold-blocks-deletion)
9.  [Look at the data](#9-look-at-the-data)
10. [Run the tests](#10-run-the-tests)
11. [Stop the service](#11-stop-the-service)
12. [If something goes wrong](#12-if-something-goes-wrong)

Architecture and the API reference are in `README.md`.

## Current setup

The service is configured for **MongoDB Atlas** and **real AWS S3**.

  Setting | Value
  --------|------
  Database | Atlas cluster, database `legal_discovery`
  Attachments | AWS S3 bucket `discoveryhub-export-bucket-2026`, region `eu-north-1`
  Kafka, Elasticsearch, MinIO | local containers
  Retention | EMAIL 7 years, CHAT 3 years
  Disposition | off (`DISPOSITION_ENABLED=false`)

Those values live in `.env` at the project root. It is git-ignored and holds
real credentials, so never commit it.

## 1. Start the service

Go to the infrastructure folder.

``` bash
cd ~/Project/Discovery-Hub/infrastructure
```

Start everything.

``` bash
docker compose up -d
```

Or start only this service and what it depends on.

``` bash
docker compose up -d ingestion-service
```

If you changed the code, rebuild. Without `--build` Docker keeps the old
image and your change will not appear.

``` bash
docker compose up -d --build ingestion-service
```

See what is running.

``` bash
docker compose ps
```

  Service | URL
  --------|----
  Ingestion API | `http://localhost:8081`
  Search API | `http://localhost:8082`
  Case & Hold API | `http://localhost:8083`
  Export & Audit API | `http://localhost:8084`
  Elasticsearch | `http://localhost:9200`
  Kafka UI | `http://localhost:8085`
  MinIO console | `http://localhost:9001`

## 2. Check it is working

Health.

``` bash
curl -s http://localhost:8081/actuator/health
```

Expect `{"groups":["liveness","readiness"],"status":"UP"}`.

Which database it connected to.

``` bash
docker logs stown-ingestion-service | grep "MongoDB ready"
```

The retention policy in force.

``` bash
docker logs stown-ingestion-service | grep "Retention policy"
```

`PT61320H` is 7 years and `PT26280H` is 3 years.

Follow the logs live. Press `Ctrl+C` to stop watching.

``` bash
docker logs -f stown-ingestion-service
```

## 3. Ingest one message

``` bash
curl -s -X POST http://localhost:8081/api/ingestion/messages -H "Content-Type: application/json" -d '{"communicationType":"EMAIL","sender":"alice.sharma@example-bank.test","recipients":["bob.patel@example-bank.test"],"subject":"Demo message","body":"This is a demo message.","messageTimestamp":"2026-09-09T03:00:00Z","externalMessageId":"demo-001"}'
```

You get HTTP `202` and a body like this.

``` json
{"requestId":"8955c698-...","deduplicationKey":"ae39cc00...","status":"RECEIVED","duplicate":false,"messageId":null}
```

`messageId` is `null` on purpose. The API only accepts the message; a
separate worker stores it a moment later and assigns the id.

**Running the same command twice does nothing the second time.** You get
`"duplicate": true` and no new message. To ingest another message, change
`externalMessageId` to `demo-002`, and so on.

## 4. Find the message id

``` bash
curl -s "http://localhost:8081/api/ingestion/requests?externalMessageId=demo-001"
```

Look for `"status":"INGESTED"` and copy the `messageId` value.

``` json
{"requestId":"8955c698-...","messageId":"965a58f9-4038-4701-bc1f-204ab250c9a4","status":"INGESTED", ...}
```

Save it in a variable so later commands are shorter. Paste your own id.

``` bash
MID=965a58f9-4038-4701-bc1f-204ab250c9a4
```

Check it.

``` bash
echo $MID
```

Retention and hold state for that message.

``` bash
curl -s "http://localhost:8081/api/ingestion/messages/$MID/retention"
```

## 5. Ingest a message with an attachment

Base64-encode the file content first.

``` bash
printf 'demo attachment payload' | base64
```

Copy the output, then paste it as `contentBase64` below.

``` bash
curl -s -X POST http://localhost:8081/api/ingestion/messages -H "Content-Type: application/json" -d '{"communicationType":"EMAIL","sender":"alice.sharma@example-bank.test","recipients":["bob.patel@example-bank.test"],"subject":"Demo with attachment","body":"Body text.","messageTimestamp":"2026-09-09T03:00:00Z","externalMessageId":"demo-002","attachments":[{"filename":"demo-note.txt","contentType":"text/plain","contentBase64":"ZGVtbyBhdHRhY2htZW50IHBheWxvYWQ="}]}'
```

For a real file, encode it like this and paste the output the same way.

``` bash
base64 -i ~/Downloads/report.pdf
```

The binary goes to the AWS bucket under
`messages/<messageId>/attachments/att-001/<filename>`.

## 6. Ingest 10,000 messages in bulk

Go to the generator folder.

``` bash
cd ~/Project/Discovery-Hub/corpus-generator
```

First time only, create the virtual environment.

``` bash
python3 -m venv .venv
```

First time only, install the dependency.

``` bash
.venv/bin/pip install -r requirements.txt
```

Start with 20 messages to check it works.

``` bash
.venv/bin/python generate_corpus.py --messages 20 --output demo-corpus --external-id-prefix demo
```

The full corpus, 10,000 messages across 25 custodians with about 8% carrying
attachments.

``` bash
.venv/bin/python generate_corpus.py --messages 10000 --custodians 25 --rate 80 --workers 8 --output aws-corpus --external-id-prefix corpus --seed 42
```

This takes roughly ten minutes. Submissions finish before storage does,
because the worker keeps processing after the generator exits.

Useful options:

  Option | Meaning
  -------|--------
  `--messages` | How many to generate.
  `--custodians` | Number of fictional employees.
  `--attachment-rate` | Fraction with attachments, `0.08` by default. Use `0` for none.
  `--external-id-prefix` | Change this to load a second corpus. Reuse it to re-submit the same one safely.
  `--dry-run` | Write files locally and call no API.
  `--resume` | Skip messages already submitted in a previous run.

## 7. Delete a message in one minute

Retention is 7 years for email, so a demo message needs a shorter one. Set
it on that single message only. The rest of the corpus then cannot be
touched, because everything else expires in 2029 or later.

### Step 1. Ingest a throwaway message

``` bash
curl -s -X POST http://localhost:8081/api/ingestion/messages -H "Content-Type: application/json" -d '{"communicationType":"EMAIL","sender":"demo.user@example-bank.test","recipients":["compliance@example-bank.test"],"subject":"DISPOSITION DEMO - safe to delete","body":"Throwaway message for the retention demonstration.","messageTimestamp":"2026-09-09T03:00:00Z","externalMessageId":"disposition-demo-1","attachments":[{"filename":"demo-note.txt","contentType":"text/plain","contentBase64":"ZGVtbyBhdHRhY2htZW50IHBheWxvYWQ="}]}'
```

### Step 2. Get its message id

``` bash
curl -s "http://localhost:8081/api/ingestion/requests?externalMessageId=disposition-demo-1"
```

Copy the `messageId` and put it in a variable.

``` bash
MID=paste-the-messageId-here
```

### Step 3. Open a MongoDB shell on Atlas

Load the connection string into your shell.

``` bash
cd ~/Project/Discovery-Hub
```

``` bash
set -a
```

``` bash
source .env
```

``` bash
set +a
```

Open the shell.

``` bash
docker run --rm -it mongo:7 mongosh "$MONGODB_URI"
```

You are now at a `legal_discovery>` prompt. The next three commands are typed
there, not in bash.

### Step 4. Shorten the retention to one minute

``` javascript
db.messages.updateOne({ externalMessageId: "disposition-demo-1" }, [ { $set: { retentionUntil: { $dateAdd: { startDate: "$createdAt", unit: "minute", amount: 1 } } } } ])
```

### Step 5. Check that nothing else is due for deletion

``` javascript
db.messages.find({ retentionUntil: { $lte: new Date() } }, { externalMessageId: 1, _id: 0 })
```

**Only `disposition-demo-1` may appear.** If any other message is listed, it
will be deleted too. Leave the shell with `exit`.

### Step 6. Turn the deletion job on

Open `.env` in an editor.

``` bash
open -e ~/Project/Discovery-Hub/.env
```

Change these two lines. Leave `RETENTION_EMAIL` and `RETENTION_CHAT` as they
are.

``` text
DISPOSITION_ENABLED=true
DISPOSITION_INTERVAL_MS=10000
```

Restart the service.

``` bash
cd ~/Project/Discovery-Hub/infrastructure
```

``` bash
docker compose up -d ingestion-service
```

### Step 7. Watch it disappear

Wait about a minute, then check the message. `404` means it is gone.

``` bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8081/api/ingestion/messages/$MID/retention"
```

See what the job did.

``` bash
curl -s "http://localhost:8081/api/ingestion/disposition/runs?limit=3"
```

Expect `"deleted": 1`. The attachment is removed from AWS as well.

``` bash
aws s3 ls "s3://discoveryhub-export-bucket-2026/messages/$MID/" --recursive
```

Empty output is correct.

### Step 8. Turn the job back off

Set these back in `.env`.

``` text
DISPOSITION_ENABLED=false
DISPOSITION_INTERVAL_MS=60000
```

``` bash
docker compose up -d ingestion-service
```

### Rehearse without deleting

To see what *would* be deleted, set this in `.env` instead of enabling
deletion, then restart.

``` text
DISPOSITION_DRY_RUN=true
```

``` bash
docker logs stown-ingestion-service | grep "dry-run"
```

## 8. Show that a legal hold blocks deletion

A message under legal hold cannot be deleted by anything, including the
retention job.

### Step 1. Create a case

``` bash
curl -s -X POST http://localhost:8083/api/v1/cases -H "Content-Type: application/json" -d '{"caseName":"Retention demo","description":"Hold blocks disposition","createdBy":"investigator"}'
```

Copy the `caseId` from the response.

``` bash
CASE_ID=paste-the-caseId-here
```

### Step 2. Place the hold

Uses `$MID` from section 7.

``` bash
curl -s -X POST "http://localhost:8083/api/v1/cases/$CASE_ID/holds" -H "Content-Type: application/json" -d "{\"name\":\"Demo hold\",\"reason\":\"Litigation hold\",\"createdBy\":\"investigator\",\"communications\":[{\"communicationId\":\"$MID\",\"communicationType\":\"EMAIL\"}]}"
```

Copy the `holdId` from the response.

``` bash
HOLD_ID=paste-the-holdId-here
```

### Step 3. Confirm the hold reached the ingestion service

``` bash
curl -s "http://localhost:8081/api/ingestion/messages/$MID/retention"
```

Expect `"held": true`, `"holdCount": 1` and `"dispositionStatus": "ON_HOLD"`.

### Step 4. Try to delete it

``` bash
curl -s -X DELETE "http://localhost:8081/api/ingestion/messages/$MID"
```

You get HTTP `409` and this message, and the message is still there.

``` json
{"status":409,"error":"Conflict","message":"Message ... is under legal hold (holdCount=1); deletion refused; holds: ..."}
```

With the retention job running, the message is skipped on every run instead
of being deleted.

``` bash
curl -s "http://localhost:8081/api/ingestion/disposition/runs?limit=3"
```

Look for `"skippedOnHold": 1`.

### Step 5. Release the hold

``` bash
curl -s -X PATCH "http://localhost:8083/api/v1/holds/$HOLD_ID/release" -H "Content-Type: application/json" -d '{"releasedBy":"investigator"}'
```

``` bash
curl -s "http://localhost:8081/api/ingestion/messages/$MID/retention"
```

`holdCount` is back to `0`. If retention has expired, the next run deletes
it.

## 9. Look at the data

### Through the API

``` bash
curl -s "http://localhost:8081/api/ingestion/requests?externalMessageId=demo-001"
```

``` bash
curl -s "http://localhost:8081/api/ingestion/messages/$MID/retention"
```

``` bash
curl -s "http://localhost:8081/api/ingestion/disposition/runs?limit=5"
```

``` bash
curl -s "http://localhost:8082/api/search?q=covenant&size=3"
```

### In Atlas

Load the connection string.

``` bash
cd ~/Project/Discovery-Hub
```

``` bash
set -a
```

``` bash
source .env
```

``` bash
set +a
```

Open the shell.

``` bash
docker run --rm -it mongo:7 mongosh "$MONGODB_URI"
```

At the `legal_discovery>` prompt, one at a time:

``` javascript
db.messages.countDocuments()
```

``` javascript
db.ingestion_requests.countDocuments({ status: "FAILED" })
```

``` javascript
db.messages.countDocuments({ outboxStatus: "PENDING" })
```

``` javascript
db.messages.countDocuments({ holdCount: { $gt: 0 } })
```

``` javascript
db.messages.countDocuments({ retentionUntil: { $lte: new Date() } })
```

``` javascript
db.disposition_runs.find().sort({ startedAt: -1 }).limit(3)
```

`FAILED` and `PENDING` should both be `0`. Leave with `exit`.

### In AWS S3

``` bash
aws s3 ls "s3://discoveryhub-export-bucket-2026/messages/" --recursive --summarize
```

``` bash
aws s3 ls "s3://discoveryhub-export-bucket-2026/staging/" --recursive --summarize
```

`staging/` should be empty. Retrieving a specific file is covered in
`../docs/s3-archival.md`.

### In Kafka

Open `http://localhost:8085` in a browser, or:

``` bash
docker exec stown-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic message.ingested --from-beginning --timeout-ms 5000
```

``` bash
docker exec stown-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:19092 --topic message.disposed --from-beginning --timeout-ms 5000
```

## 10. Run the tests

``` bash
cd ~/Project/Discovery-Hub/ingestion-service
```

``` bash
./mvnw test
```

46 tests. Docker must be running, because the integration tests start their
own MongoDB, Kafka and MinIO containers. They do not touch Atlas or AWS and
need no `.env`.

One test class only.

``` bash
./mvnw test -Dtest=RetentionDispositionIntegrationTest
```

## 11. Stop the service

``` bash
cd ~/Project/Discovery-Hub/infrastructure
```

Stop just this service.

``` bash
docker compose stop ingestion-service
```

Stop everything.

``` bash
docker compose down
```

Stop everything and delete the local container data.

``` bash
docker compose down -v
```

`down -v` deletes local MongoDB, MinIO and Elasticsearch volumes. It does not
touch Atlas or the AWS bucket.

## 12. If something goes wrong

**`404` on a POST.** The path is `/api/ingestion/messages`. Not
`/api/messages`.

**`"duplicate": true` and no new message.** That `externalMessageId` was
already used. Change it.

**`503 Attachment storage is unavailable`.** S3 rejected the upload. The
message names the reason. Usually the AWS keys are wrong or expired, or
`AWS_REGION` does not match the bucket.

**Status stays `RECEIVED`.** The worker is not consuming. Check Kafka is up.

``` bash
docker compose ps
```

Also make sure the service is not running in your IDE at the same time as the
container. Two copies share port 8081 and the same Kafka group.

**Code changes do nothing.** Rebuild.

``` bash
docker compose up -d --build ingestion-service
```

**The service will not start, with a retention error.** This is deliberate.

``` text
Retention period for EMAIL is PT1M, below the configured floor of PT24H.
```

A one-minute retention applied to everything would destroy real data. Use
section 7 instead, which shortens one message only.

**Nothing gets deleted.** `DISPOSITION_ENABLED` is `false` by default.

``` bash
docker logs stown-ingestion-service | grep "Retention policy"
```

**`docker compose` complains the YAML is invalid.** Someone introduced a
duplicate key. Check it parses.

``` bash
docker compose config --quiet
```

**Cannot reach Atlas.** Your IP must be allowed in Atlas under Network
Access, and the password in `MONGODB_URI` must be percent-encoded if it
contains special characters.

``` bash
docker logs stown-ingestion-service | grep -i "mongo"
```
