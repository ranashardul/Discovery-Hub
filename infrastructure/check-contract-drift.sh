#!/usr/bin/env bash
#
# Reports field-level drift between the contracts each service keeps its own
# copy of.
#
# There is no shared module: MessageDocument, AttachmentMetadata,
# MessageIngestedEvent, MessageDisposedEvent and AuditEvent exist as
# independent classes per service, bound by field name over Kafka or MongoDB.
# So renaming a field in one produces no compile error anywhere else — the
# other side silently reads null, and the failure surfaces much later as
# missing data rather than a build break.
#
# AGENTS.md says to diff these by hand whenever the model changes. This does it.
#
#   ./check-contract-drift.sh          # report, exit 1 on drift
#   ./check-contract-drift.sh --quiet  # exit code only
#
# A field present in one copy and absent from another is not automatically
# wrong: a consumer may legitimately ignore fields it does not need. What
# matters is that every difference is *known*. Fields a consumer deliberately
# does not carry are listed in EXPECTED_ABSENT below, and anything else is
# reported.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

DRIFT=0

say() { [ "$QUIET" -eq 1 ] || printf '%s\n' "$1"; }
bold() { [ "$QUIET" -eq 1 ] || printf '\033[1m%s\033[0m\n' "$1"; }
red() { [ "$QUIET" -eq 1 ] || printf '\033[31m%s\033[0m\n' "$1"; }
green() { [ "$QUIET" -eq 1 ] || printf '\033[32m%s\033[0m\n' "$1"; }
dim() { [ "$QUIET" -eq 1 ] || printf '\033[2m%s\033[0m\n' "$1"; }

# Fields a given copy deliberately does not carry, as
# "<Class>:<service>:<field>". Each is a decision, not an oversight, so
# adding to this list should be a deliberate act with a reason.
EXPECTED_ABSENT=(
  # These three services read the messages collection but never write to it,
  # so the ingestion bookkeeping fields are of no use to them.
  "MessageDocument:case-hold-service:outboxStatus"
  "MessageDocument:case-hold-service:outboxPublishedAt"
  "MessageDocument:case-hold-service:outboxAttempts"
  "MessageDocument:case-hold-service:outboxLastError"
  "MessageDocument:case-hold-service:requestId"
  "MessageDocument:export-audit-service:outboxStatus"
  "MessageDocument:export-audit-service:outboxPublishedAt"
  "MessageDocument:export-audit-service:outboxAttempts"
  "MessageDocument:export-audit-service:outboxLastError"
  "MessageDocument:export-audit-service:requestId"
  "MessageDocument:search-service:outboxStatus"
  "MessageDocument:search-service:outboxPublishedAt"
  "MessageDocument:search-service:outboxAttempts"
  "MessageDocument:search-service:outboxLastError"
  "MessageDocument:search-service:requestId"

  # "A case references communications, it does not hold their content."
  # Packaging content is the export service's job, so case-hold maps only the
  # metadata a reviewer needs to recognise a message.
  "MessageDocument:case-hold-service:body"
  "MessageDocument:case-hold-service:deduplicationKey"
  "MessageDocument:case-hold-service:externalMessageId"

  # The export service packages the evidence it is handed. Which messages are
  # in scope comes from case-hold, so it never needs to read hold state or a
  # retention clock off the message.
  "MessageDocument:export-audit-service:dispositionStatus"
  "MessageDocument:export-audit-service:holdCount"
  "MessageDocument:export-audit-service:holdIds"
  "MessageDocument:export-audit-service:retentionUntil"

  # Retention is enforced by ingestion; the index has no use for the clock.
  "MessageDocument:search-service:retentionUntil"

  # case-hold never fetches an attachment binary, so it does not map where one
  # lives or what it hashes to.
  "AttachmentMetadata:case-hold-service:s3Bucket"
  "AttachmentMetadata:case-hold-service:s3Key"
  "AttachmentMetadata:case-hold-service:s3Url"
  "AttachmentMetadata:case-hold-service:sha256"
)

is_expected_absent() {
  local needle="$1:$2:$3"
  for entry in "${EXPECTED_ABSENT[@]}"; do
    [ "$entry" = "$needle" ] && return 0
  done
  return 1
}

# Field names declared on a class. Lombok @Data classes and records both
# reduce to "a type followed by a name", so this reads the private fields of a
# class and the components of a record.
fields_of() {
  python3 - "$1" <<'PY'
import re, sys

source = open(sys.argv[1]).read()

# Strip comments so a field name inside prose is never picked up.
source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
source = re.sub(r'//[^\n]*', '', source)

fields = []

record = re.search(r'\brecord\s+\w+\s*\((.*?)\)\s*\{', source, re.S)
if record:
    for component in record.group(1).split(','):
        name = component.strip().split()
        if len(name) >= 2:
            fields.append(name[-1])
else:
    for match in re.finditer(r'^\s*private\s+(?:final\s+)?[\w<>,\[\]\s\.]+?\s+(\w+)\s*[;=]',
                             source, re.M):
        fields.append(match.group(1))

for field in sorted(set(fields)):
    print(field)
PY
}

service_of() {
  # ./ingestion-service/src/... -> ingestion-service
  printf '%s' "${1#./}" | cut -d/ -f1
}

compare() {
  local class="$1"
  shift
  local -a paths=("$@")

  if [ "${#paths[@]}" -lt 2 ]; then
    return
  fi

  bold "$class"

  # The first copy is the reference. Ingestion owns the message model and
  # publishes the events, so where it is present it is the producer.
  local reference="${paths[0]}"
  for path in "${paths[@]}"; do
    case "$path" in */ingestion-service/*) reference="$path" ;; esac
  done

  local reference_service
  reference_service=$(service_of "$reference")
  local reference_fields
  reference_fields=$(fields_of "$reference")

  dim "  reference: $reference_service ($(printf '%s' "$reference_fields" | grep -c . ) fields)"

  local class_drift=0

  for path in "${paths[@]}"; do
    [ "$path" = "$reference" ] && continue

    local service
    service=$(service_of "$path")
    local other
    other=$(fields_of "$path")

    local missing extra
    missing=$(comm -23 <(printf '%s\n' "$reference_fields") <(printf '%s\n' "$other"))
    extra=$(comm -13 <(printf '%s\n' "$reference_fields") <(printf '%s\n' "$other"))

    local reported=0

    while IFS= read -r field; do
      [ -z "$field" ] && continue
      if is_expected_absent "$class" "$service" "$field"; then
        continue
      fi
      red "  $service is missing '$field'"
      dim "    $reference_service declares it; this copy will read null"
      reported=1
    done <<<"$missing"

    while IFS= read -r field; do
      [ -z "$field" ] && continue
      red "  $service declares '$field', which $reference_service does not"
      dim "    nothing will ever populate it"
      reported=1
    done <<<"$extra"

    if [ "$reported" -eq 1 ]; then
      class_drift=1
      DRIFT=1
    else
      green "  $service matches"
    fi
  done

  [ "$class_drift" -eq 0 ] && dim "  no drift"
  say ""
}

bold "Contract drift across independently-copied classes"
say ""

# `mapfile` is bash 4; macOS ships 3.2, so the paths are collected with a
# read loop instead.
for class in MessageDocument AttachmentMetadata MessageIngestedEvent MessageDisposedEvent AuditEvent; do
  paths=()
  while IFS= read -r path; do
    [ -n "$path" ] && paths+=("$path")
  done < <(find . -name "$class.java" -not -path "*/target/*" | sort)

  if [ "${#paths[@]}" -gt 0 ]; then
    compare "$class" "${paths[@]}"
  else
    dim "$class: no copies found"
  fi
done

if [ "$DRIFT" -eq 1 ]; then
  red "Contract drift detected."
  dim "These classes are bound by field name over Kafka and MongoDB, so a"
  dim "mismatch does not fail the build — the receiving side reads null and the"
  dim "data goes missing instead. Reconcile them, or record a deliberate"
  dim "omission in EXPECTED_ABSENT in this script."
  exit 1
fi

green "No unexpected contract drift."
