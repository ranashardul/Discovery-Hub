# Discovery Hub - Corpus Generator

A single-file Python tool that generates a realistic **synthetic** corpus of
**retail and corporate banking** communications (EMAIL + CHAT, with attachments) and
submits it to the Discovery Hub ingestion service at
`POST {api-url}/api/ingestion/messages`.

Everything it produces is fictional. People, borrowers, counterparty banks, account
numbers and references come from built-in templated vocabularies, and the email domain
is `example-bank.test` (a reserved RFC 2606 TLD that can never resolve to a real
mailbox). **No real customer, account or transaction data is used or required.**

- 10,000+ messages supported (default `--messages 10000`)
- 25 fictional custodians by default across 12 banking departments
- ~65% EMAIL / ~35% CHAT, threaded conversations of 1-6 messages
- Multi-paragraph email bodies (~300 words median) with a bulleted figures block
- ~8% of messages carry attachments in 7 formats and 5 size bands (5 KB - 1 MB)
- Timestamps spread over the last 365 days, business-hours weighted, chronological per thread
- Fully deterministic for a given `--seed`, so re-runs are idempotency-safe

## Banking Content

Ten conversation themes, each with its own subjects, background, details, summary
bullets, risk assessments, actions and chat style:

| Theme | Covers |
|---|---|
| `credit` | Credit papers, sanction conditions, covenants, rating migration, security perfection |
| `aml` | Transaction-monitoring alerts, structuring, EDD, sanctions screening, KYC remediation, SAR drafting |
| `treasury` | LCR/NSFR, ALCO packs, funding plans, deposit concentration, intraday liquidity |
| `regulatory` | Basel returns, capital adequacy, data quality, inspection findings, large exposures |
| `payments` | Settlement breaks, nostro reconciliation, rail outages, cut-off breaches, investigations |
| `collections` | NPA classification, recovery strategy, one-time settlements, delinquency buckets |
| `fraud` | Card fraud patterns, chargeback disputes, account takeover, mule networks |
| `trade_finance` | Letters of credit, discrepancies, guarantee calls, bill discounting, confirmations |
| `wealth` | Suitability reviews, mandate breaches, mis-selling complaints, performance reviews |
| `audit` | Internal audit findings, delegated authority breaches, access recertification, remediation |

Departments: Credit Risk, Corporate Banking, Retail Banking, Treasury, Financial Crime
Compliance, Regulatory Reporting, Payments Operations, Trade Finance, Collections and
Recoveries, Wealth Management, Internal Audit, Legal.

Facts are resolved **once per conversation**, so a thread stays about the same
borrower, reporting period, portfolio and reference number instead of drifting
between paragraphs. Attachment names are theme-aligned: a credit thread carries a
`credit-memo`, a trade thread carries `lc-documents`. CSV attachments are transaction
extracts with masked account numbers.

---

## Requirements

- Python 3.11+
- `requests` (stdlib for everything else)
- `boto3` only if you use the optional `--upload-to-s3` archival flag

## Install

```bash
cd corpus-generator
python3 -m venv .venv
source .venv/bin/activate           # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

Configuration comes from the single project-level environment file at the
repository root (there is no per-module `.env`):

```bash
cp ../.env.example ../.env
```

`.env` is **not** auto-loaded; export the variables (e.g. `set -a; source ../.env; set +a`)
or just pass CLI flags. CLI flags always take precedence over environment variables.
The generator reads the `API_URL` and `CORPUS_*` variables.

---

## Usage

### Smoke test (20 messages, 20 custodians, 2 req/s)

```bash
python generate_corpus.py \
  --messages 20 \
  --custodians 20 \
  --rate 2 \
  --output test-corpus \
  --api-url http://localhost:8081
```

Add `--dry-run` to generate the files locally without calling the API:

```bash
python generate_corpus.py --messages 20 --custodians 20 --rate 2 --output test-corpus --dry-run
```

### Full corpus (10,000 messages, 25 custodians)

```bash
python generate_corpus.py \
  --messages 10000 \
  --custodians 25 \
  --rate 20 \
  --workers 4 \
  --output generated-corpus \
  --api-url http://localhost:8081 \
  --seed 42
```

At `--rate 20` a 10,000-message run takes roughly 8-9 minutes. If the run is
interrupted, re-run the identical command with `--resume` and it will skip everything
already recorded as accepted:

```bash
python generate_corpus.py --messages 10000 --custodians 25 --rate 20 --workers 4 \
  --output generated-corpus --api-url http://localhost:8081 --seed 42 --resume
```

---

## CLI flags

| Flag | Env var | Default | Description |
|------|---------|---------|-------------|
| `--messages` | `CORPUS_MESSAGES` | `10000` | Number of messages to generate. |
| `--custodians` | `CORPUS_CUSTODIANS` | `25` | Number of fictional custodians. |
| `--rate` | `CORPUS_RATE` | `20` | Max total submissions per second (client-side throttle, shared across workers). |
| `--output` | `CORPUS_OUTPUT` | `generated-corpus` | Output directory. |
| `--api-url` | `API_URL` | `http://localhost:8081` | Base URL of the ingestion service. |
| `--attachment-rate` | `CORPUS_ATTACHMENT_RATE` | `0.08` | Fraction of messages carrying attachments. |
| `--email-ratio` | `CORPUS_EMAIL_RATIO` | `0.65` | Fraction of threads that are EMAIL (rest are CHAT). |
| `--seed` | `CORPUS_SEED` | `42` | Random seed; same seed = byte-identical corpus. |
| `--external-id-prefix` | `CORPUS_ID_PREFIX` | `corpus` | Prefix for `externalMessageId`. The API deduplicates on this value: reuse it to re-submit idempotently, change it to load a separate corpus alongside an existing one. |
| `--window-days` | `CORPUS_WINDOW_DAYS` | `365` | Length of the timestamp window ending today (UTC). |
| `--dry-run` | - | off | Generate files locally, never call the API. |
| `--resume` | - | off | Skip messages already recorded as accepted in `messages.jsonl`. |
| `--workers` | `CORPUS_WORKERS` | `4` | Concurrent submitter threads (total rate still capped by `--rate`). |
| `--timeout` | `CORPUS_TIMEOUT` | `30` | HTTP timeout per request, in seconds. |
| `--max-attachment-bytes` | `CORPUS_MAX_ATTACHMENT_BYTES` | `1048576` | Hard cap per attachment; larger targets are rebuilt smaller. |
| `--include-base64-in-jsonl` | - | off | Also record attachment base64 in `messages.jsonl` (very large; off by default). |
| `--upload-to-s3` | - | off | Additionally archive local attachment files to S3/MinIO. |
| `--s3-prefix` | `CORPUS_S3_PREFIX` | `corpus-attachments` | Key prefix for the S3 archival step. |

Exit code is `0` when nothing failed and `1` when at least one message was rejected.

---

## Output

Everything is written under `--output`:

| Path | Contents |
|------|----------|
| `custodians.json` | Array of `{custodianId, fullName, email, department, jobTitle}`. |
| `messages.jsonl` | One JSON object per message: the exact API request payload plus a `_submission` object `{externalMessageId, httpStatus, requestId, deduplicationKey, error, submittedAt}`. Appended as messages are processed, so it is also the `--resume` ledger. |
| `attachments/` | The generated attachment binaries, named `{externalMessageId}-{index}-{filename}`. |
| `summary.json` | `{total, email, chat, withAttachments, attachmentCount, custodians, threads, submitted, failed, durationSeconds, skippedByResume, attachmentBytes}`. |

By default the `attachments` array inside `messages.jsonl` records
`{filename, contentType, sizeBytes, localPath, sha256}` instead of the base64 blob, so
the ledger stays small and greppable; the base64 *is* sent to the API. Pass
`--include-base64-in-jsonl` if you want the literal wire payload on disk too (expect
hundreds of MB at 10k messages).

Progress is printed every 100 messages (`submitted`, `failed`, current rate, elapsed),
followed by a final summary block.

### Attachments

| Type | Extension | Notes |
|------|-----------|-------|
| PDF | `.pdf` | Real PDF 1.7 header, object table, `xref`, `trailer`, `%%EOF`; padded with PDF comments. |
| Word | `.docx` | ZIP with `[Content_Types].xml`, `_rels/.rels`, `word/document.xml`. |
| Excel | `.xlsx` | ZIP with workbook, worksheet and relationship parts, inline-string rows. |
| CSV | `.csv` | Real ledger-style CSV with a header row. |
| Text | `.txt` | Plain-text memo. |
| PNG | `.png` | Real PNG signature + `IHDR` + `IDAT` + `IEND`, padded via a `tEXt` chunk. |
| ZIP | `.zip` | Real archive containing a memo, a CSV and a stored payload entry. |

Target sizes are drawn from ~5 KB / 25 KB / 100 KB / 500 KB / 1 MB and never exceed
`--max-attachment-bytes` (default 1 MiB) since attachments are inlined as base64.

---

## API contract used

```http
POST {api-url}/api/ingestion/messages
Content-Type: application/json

{
  "communicationType": "EMAIL",
  "sender": "arjun.patel@example-bank.test",
  "recipients": ["meera.patel@example-bank.test"],
  "subject": "Credit paper: Ironvale Cement - USD 420k facility",
  "body": "Attaching the credit paper for the Ironvale Cement facility ahead of ...",
  "messageTimestamp": "2026-09-08T03:00:00Z",
  "threadId": "thread-00001",
  "externalMessageId": "corpus-000001",
  "attachments": [
    { "filename": "credit-memo-000001-1.pdf", "contentType": "application/pdf", "contentBase64": "..." }
  ]
}
```

- **HTTP 202** `{requestId, deduplicationKey, status: "RECEIVED", duplicate, messageId}` = accepted.
- **HTTP 400** with `fieldErrors[]` = validation failure; recorded as a failure and **never retried**.
- 5xx responses, timeouts and connection errors are retried up to 3 times with
  exponential backoff (1s, 2s).

`externalMessageId` is always sent and is stable for a given `--seed`, so re-running the
same command is idempotent: the service returns 202 with `"duplicate": true`, and the
run summary reports those under `Accepted as duplicate`.

Because the API deduplicates on `externalMessageId`, loading a **different** corpus into
a database that already used the default prefix requires a new one:

```bash
python generate_corpus.py --messages 5000 --external-id-prefix corpus-2026-q3 ...
```

Without that, the new messages are correctly recognised as re-submissions of the
existing source IDs and no new documents are created.

CHAT messages always carry a non-blank, short conversation-topic subject, because the
API requires `subject` for every communication type.

> **Ingestion is asynchronous.** A 202 only means the request was accepted and queued.
> Messages are persisted by the ingestion worker, so they appear in MongoDB shortly
> after submission, not at the instant the generator finishes. Let the worker drain
> before asserting document counts.

---

## Optional: archive attachments to S3 / MinIO

The primary path always sends attachments to the API as inline base64. `--upload-to-s3`
is a separate, best-effort archival step that copies the local files from
`{output}/attachments/` into a bucket:

```bash
export S3_BUCKET=discovery-hub-attachments
export S3_ENDPOINT=http://localhost:9000
export AWS_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...

python generate_corpus.py --messages 20 --custodians 20 --rate 2 \
  --output test-corpus --upload-to-s3
```

If `boto3` is not installed or `S3_BUCKET` is unset, the generator prints a warning,
records the reason in `summary.json` (`s3Error`) and continues - the corpus run itself
is never failed by the archival step. **Never commit real credentials.**

---

## Determinism notes

With the same `--seed`, `--messages`, `--custodians` and `--window-days`, the generator
produces identical custodians, message bodies, `externalMessageId`s and attachment bytes
(ZIP-based formats use a fixed internal timestamp). The timestamp window is anchored on
UTC midnight rather than the current clock, so re-runs on the same day are byte-identical;
a run on a later date shifts the window forward by whole days.
