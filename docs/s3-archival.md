# S3 Archival Storage and Retrieval

Attachment binaries are archived in S3; MongoDB holds only the metadata and
the object location. This document covers how the bucket is configured and
how to get files back out when they are needed.

## Bucket

``` text
arn:aws:s3:::discoveryhub-export-bucket-2026
region: eu-north-1
```

This is the archival bucket. Bucket ARNs carry no region or account ID, so
the region has to be configured separately, and it must be correct: a
mismatch fails with `PermanentRedirect` rather than a useful error. To check
the region of any bucket without credentials:

``` bash
curl -sI https://discoveryhub-export-bucket-2026.s3.amazonaws.com \
  | grep -i x-amz-bucket-region
```

## Object Layout

``` text
staging/{requestId}/{index}-{filename}                       transient
messages/{messageId}/attachments/{attachmentId}/{filename}   archived
```

The API uploads to `staging/` before publishing the Kafka event, so binaries
never travel through Kafka. The worker copies the object to its durable
`messages/` key and deletes the staged copy. Anything left under `staging/`
belongs to a request that never completed.

Because the key contains the immutable `messageId`, every attachment for a
message shares one prefix, which is what makes retrieval and legal-hold
collection straightforward.

## Service Configuration

Set in the project-level `.env`:

``` text
S3_BUCKET=discoveryhub-export-bucket-2026
S3_ENDPOINT=                       # empty means real AWS S3
S3_PATH_STYLE_ACCESS=false         # virtual-hosted style; true only for MinIO
S3_CREATE_BUCKET=false             # bucket already exists
S3_PUBLIC_BASE_URL=                # empty builds the standard https URL
AWS_REGION=eu-north-1
AWS_ACCESS_KEY_ID=<your-access-key>
AWS_SECRET_ACCESS_KEY=<your-secret-key>
```

Credentials resolve through the standard AWS chain, so environment variables,
a shared credentials file, an instance profile or an assumed role all work
without code changes. Nothing is hardcoded and `.env` is git-ignored.

To run against local MinIO instead, set `S3_ENDPOINT=http://minio:9000`,
`S3_PATH_STYLE_ACCESS=true` and `S3_CREATE_BUCKET=true`.

## Required IAM Permissions

The ingestion service needs:

``` json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ArchiveAttachments",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::discoveryhub-export-bucket-2026/*"
    },
    {
      "Sid": "ListForVerification",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetBucketLocation"
      ],
      "Resource": "arn:aws:s3:::discoveryhub-export-bucket-2026"
    }
  ]
}
```

Notes on the individual actions:

-   `PutObject` is used by the API for the staging upload.
-   `GetObject` is required for the server-side copy, because a copy reads the
    source object. Without it the worker fails on every attachment.
-   `DeleteObject` removes the staged copy. Without it ingestion still
    succeeds, but `staging/` grows without bound.
-   `ListBucket` is not needed by the service, only by the verification
    commands below.
-   `S3_CREATE_BUCKET=false` means `s3:CreateBucket` is not required.

## Finding an Object

MongoDB is the index. Every attachment record carries the bucket, key, URL,
size and SHA-256 checksum:

``` javascript
db.messages.findOne(
  { externalMessageId: "corpus-000042" },
  { attachments: 1 }
)
```

``` javascript
{
  attachments: [
    {
      attachmentId: "att-001",
      filename: "credit-memo-000042-1.pdf",
      contentType: "application/pdf",
      sizeBytes: 101719,
      sha256: "9bf16bb40ae596c8...",
      s3Bucket: "discoveryhub-export-bucket-2026",
      s3Key: "messages/bf5e646d-.../attachments/att-001/credit-memo-000042-1.pdf",
      s3Url: "https://discoveryhub-export-bucket-2026.s3.eu-north-1.amazonaws.com/messages/..."
    }
  ]
}
```

Useful queries:

``` javascript
// Every attachment for one message
db.messages.findOne({ _id: "<messageId>" }, { attachments: 1 })

// All attachments of a given type
db.messages.find(
  { "attachments.contentType": "application/pdf" },
  { "attachments.s3Key": 1 }
)

// Everything in a conversation, for a legal hold
db.messages.find({ threadId: "thread-00042" }, { attachments: 1 })

// Total archived volume
db.messages.aggregate([
  { $unwind: "$attachments" },
  { $group: { _id: null, files: { $sum: 1 }, bytes: { $sum: "$attachments.sizeBytes" } } }
])
```

## Retrieving Files

### The stored URL is not publicly readable

`s3Url` records where the object lives. Bucket objects are private by
default, so opening that URL in a browser returns `403 AccessDenied`. Use one
of the authenticated methods below.

### AWS CLI

``` bash
export AWS_ACCESS_KEY_ID=<your-access-key>
export AWS_SECRET_ACCESS_KEY=<your-secret-key>
export AWS_REGION=eu-north-1

# One object, using the s3Key from MongoDB
aws s3 cp "s3://discoveryhub-export-bucket-2026/messages/<messageId>/attachments/att-001/file.pdf" .

# Every attachment for one message
aws s3 cp "s3://discoveryhub-export-bucket-2026/messages/<messageId>/attachments/" . --recursive

# Inspect without downloading
aws s3api head-object \
  --bucket discoveryhub-export-bucket-2026 \
  --key "messages/<messageId>/attachments/att-001/file.pdf"
```

Without the CLI installed, the official image works the same way:

``` bash
docker run --rm -v "$PWD:/out" \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_REGION \
  amazon/aws-cli:latest \
  s3 cp "s3://discoveryhub-export-bucket-2026/<key>" /out/
```

### Presigned URL for temporary sharing

A presigned URL is the right way to hand a file to someone without giving
them credentials. It embeds a signature and expires:

``` bash
aws s3 presign \
  "s3://discoveryhub-export-bucket-2026/messages/<messageId>/attachments/att-001/file.pdf" \
  --expires-in 3600
```

The resulting link works in any browser until it expires. Treat it as a
secret: anyone holding it has the file. Note that presigned URLs generated
with a temporary session credential expire when the session does, whichever
comes first.

### Bulk export for a case

``` bash
# Collect the keys for a set of messages from MongoDB, then fetch them
docker run --rm mongo:7 mongosh "$MONGODB_URI" --quiet --eval '
  db.messages.find({ threadId: "thread-00042" }).forEach(m =>
    (m.attachments || []).forEach(a => print(a.s3Key)))
' > keys.txt

while read -r key; do
  aws s3 cp "s3://discoveryhub-export-bucket-2026/$key" "./export/$(basename "$key")"
done < keys.txt
```

### From application code

``` java
S3Client s3 = S3Client.builder().region(Region.EU_NORTH_1).build();

ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
        GetObjectRequest.builder()
                .bucket(attachment.getS3Bucket())
                .key(attachment.getS3Key())
                .build());
```

The service already configures an `S3Client` bean, so a future export service
can inject it rather than building its own.

## Verifying Integrity

Every attachment record stores the SHA-256 of the bytes as uploaded, so a
retrieved file can be proven identical to the ingested one:

``` bash
aws s3 cp "s3://discoveryhub-export-bucket-2026/<key>" ./file
shasum -a 256 ./file          # compare with attachments.sha256 in MongoDB
```

This is what makes the archive defensible: the checksum is recorded at
ingestion time, before the object is copied to its archival key.

## Operational Checks

``` bash
# Archived object count
aws s3 ls "s3://discoveryhub-export-bucket-2026/messages/" --recursive --summarize | tail -3

# Orphaned staging objects: requests that never completed
aws s3 ls "s3://discoveryhub-export-bucket-2026/staging/" --recursive --summarize | tail -3
```

The object count under `messages/` should equal the number of attachment
records in MongoDB:

``` javascript
db.messages.aggregate([
  { $unwind: "$attachments" },
  { $count: "attachmentRecords" }
])
```

A persistent gap means either failed copies (check for `FAILED` ingestion
requests) or missing `s3:DeleteObject` permission leaving staged duplicates.

## Recommended Bucket Settings

Not configured by this service, but appropriate for an archival bucket:

-   **Block all public access** at the bucket level. Retrieval is
    authenticated or presigned, so nothing needs public read.
-   **Default encryption** with SSE-S3 or SSE-KMS. No code change is needed;
    S3 encrypts on write.
-   **Versioning** so an overwrite or delete is recoverable, which matters for
    material under legal hold.
-   **Object Lock** in compliance mode if retention must be provably immune to
    deletion, including by administrators.
-   **Lifecycle rules**: transition `messages/` to Glacier Instant Retrieval or
    Deep Archive once the retention period no longer requires fast access, and
    expire `staging/` after a day or two to clean up abandoned uploads.
-   **Server access logging or CloudTrail data events** so every retrieval is
    auditable, which a discovery process will eventually be asked to prove.

Note that a Glacier lifecycle transition changes retrieval: objects in Deep
Archive need a `restore-object` call and hours of lead time before they can be
downloaded, which conflicts with any expectation of immediate access.
