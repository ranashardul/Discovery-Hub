#!/usr/bin/env bash
#
# End-to-end smoke test across all five services.
#
# Walks the path a reviewer actually takes — ingest, search, scope a case,
# preserve it, prove deletion is refused, export it, verify the package, read
# the chain of custody back — and asserts each step against a running stack.
#
# Every request goes through the gateway, so this exercises the same origin and
# the same routes the browser uses. A service reachable only on its own port
# would pass a direct test and fail the app.
#
#   cd infrastructure && docker compose up -d --build
#   ./smoke-test.sh
#
# Exits non-zero on the first failure, with the response that failed.

set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
SEARCH_DIRECT="${SEARCH_DIRECT:-http://localhost:8082}"

# Search must be able to see an ingested message within this long. The stated
# target is 30s; the ceiling here is deliberately higher so a slow laptop
# reports a real failure rather than a flake.
INDEX_TIMEOUT_SECONDS="${INDEX_TIMEOUT_SECONDS:-45}"
EXPORT_TIMEOUT_SECONDS="${EXPORT_TIMEOUT_SECONDS:-90}"
# Hold state reaches the index via the outbox, so convergence is bounded by
# OUTBOX_INTERVAL_MS (15s by default) plus indexing.
PROJECTION_TIMEOUT_SECONDS="${PROJECTION_TIMEOUT_SECONDS:-45}"

RUN_ID="smoke-$(date +%s)-$$"
PASSED=0

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
dim()   { printf '\033[2m%s\033[0m\n' "$1"; }

pass() { PASSED=$((PASSED + 1)); green "  ok   $1"; }

fail() {
  red "  FAIL $1"
  if [ -n "${2:-}" ]; then
    dim "       $2"
  fi
  exit 1
}

step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# json <json> <python-expression-over-`d`>
json() {
  printf '%s' "$1" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print('')
    sys.exit(0)
print($2)
" 2>/dev/null
}

# request <method> <path> [body] -> sets REPLY_CODE and REPLY_BODY
request() {
  local method="$1" path="$2" body="${3:-}"
  local response

  if [ -n "$body" ]; then
    response=$(curl -sS -X "$method" "$GATEWAY$path" \
      -H 'Content-Type: application/json' -d "$body" -w $'\n%{http_code}')
  else
    response=$(curl -sS -X "$method" "$GATEWAY$path" -w $'\n%{http_code}')
  fi

  REPLY_CODE="${response##*$'\n'}"
  REPLY_BODY="${response%$'\n'*}"
}

expect() {
  local expected="$1" label="$2"
  if [ "$REPLY_CODE" = "$expected" ]; then
    pass "$label"
  else
    fail "$label (expected HTTP $expected, got $REPLY_CODE)" "$REPLY_BODY"
  fi
}

# ---------------------------------------------------------------- preflight

step "Preflight: every service reachable through the gateway"

for service in ingestion search case-hold export-audit; do
  request GET "/actuator/health/$service"
  status=$(json "$REPLY_BODY" "d.get('status')")
  if [ "$status" = "UP" ]; then
    pass "$service is UP"
  else
    fail "$service is not UP" "$REPLY_BODY"
  fi
done

request GET "/"
expect 200 "the web app is served"

# ------------------------------------------------------------------ ingest

step "Ingestion: accept a message"

request POST "/api/ingestion/messages" "$(cat <<JSON
{
  "externalMessageId": "$RUN_ID",
  "communicationType": "EMAIL",
  "sender": "smoke.sender@stown.com",
  "recipients": ["smoke.recipient@stown.com"],
  "subject": "Smoke test $RUN_ID",
  "body": "Unique smoke marker $RUN_ID for the end to end walk.",
  "messageTimestamp": "2026-09-01T09:00:00Z",
  "threadId": "thread-$RUN_ID"
}
JSON
)"
expect 202 "message accepted for ingestion"

# ------------------------------------------------------------------ search

step "Search: the message becomes searchable"

MESSAGE_ID=""
for elapsed in $(seq 1 "$INDEX_TIMEOUT_SECONDS"); do
  request GET "/api/search?q=$RUN_ID&size=1"
  MESSAGE_ID=$(json "$REPLY_BODY" "(d.get('results') or [{}])[0].get('messageId') or ''")
  if [ -n "$MESSAGE_ID" ]; then
    pass "indexed and searchable after ${elapsed}s"
    break
  fi
  sleep 1
done

[ -n "$MESSAGE_ID" ] || fail "not searchable within ${INDEX_TIMEOUT_SECONDS}s" "$REPLY_BODY"

request GET "/api/search?q=$RUN_ID&sort=newest&size=1"
expect 200 "chronological sort works"

request GET "/api/search?sender=smoke.sender@stown.com&size=1"
expect 200 "a filter-only search works"

request GET "/api/search/ids?q=$RUN_ID"
ids_total=$(json "$REPLY_BODY" "d.get('total')")
[ "$ids_total" = "1" ] \
  && pass "bulk id resolution returns the match" \
  || fail "bulk id resolution returned total=$ids_total" "$REPLY_BODY"

request GET "/api/search/custodians"
has_sender=$(json "$REPLY_BODY" "any(c.get('name') == 'smoke.sender@stown.com' for c in d)")
[ "$has_sender" = "True" ] \
  && pass "the sender appears as a custodian" \
  || fail "the sender is missing from the custodian aggregation" "$REPLY_BODY"

# -------------------------------------------------------------------- case

step "Case & hold: scope the message onto a case"

request POST "/api/v1/cases" \
  "{\"caseName\":\"Smoke $RUN_ID\",\"description\":\"automated\",\"createdBy\":\"smoke-test\"}"
expect 200 "case created"
CASE_ID=$(json "$REPLY_BODY" "d.get('caseId')")
[ -n "$CASE_ID" ] || fail "no caseId returned" "$REPLY_BODY"

case_status=$(json "$REPLY_BODY" "d.get('status')")
[ "$case_status" = "OPEN" ] \
  && pass "a new case is OPEN" \
  || fail "unexpected new-case status: $case_status" "$REPLY_BODY"

request POST "/api/v1/cases/$CASE_ID/communications" \
  "{\"communications\":[{\"communicationId\":\"$MESSAGE_ID\"}],\"addedBy\":\"smoke-test\"}"
expect 200 "message added as evidence"

unresolved=$(json "$REPLY_BODY" "d.get('unresolvedCount')")
[ "$unresolved" = "0" ] \
  && pass "the evidence reference resolved to a real message" \
  || fail "unresolvedCount=$unresolved: case-hold cannot see the message store" "$REPLY_BODY"

request POST "/api/v1/cases/$CASE_ID/saved-searches" \
  "{\"name\":\"Smoke scope\",\"criteria\":{\"q\":\"$RUN_ID\"},\"createdBy\":\"smoke-test\"}"
expect 201 "saved search stored against the case"

request POST "/api/v1/holds/preview" '{"participants":["smoke.sender@stown.com"]}'
preview=$(json "$REPLY_BODY" "d.get('matchingCount')")
[ "${preview:-0}" -ge 1 ] \
  && pass "hold scope preview counts the message ($preview)" \
  || fail "hold scope preview returned $preview" "$REPLY_BODY"

# -------------------------------------------------------------------- hold

step "Legal hold: preserve it and prove deletion is refused"

request POST "/api/v1/cases/$CASE_ID/holds" "$(cat <<JSON
{
  "name": "Smoke hold",
  "reason": "end to end verification",
  "createdBy": "smoke-test",
  "communications": [{"communicationId": "$MESSAGE_ID"}]
}
JSON
)"
expect 200 "hold placed"
HOLD_ID=$(json "$REPLY_BODY" "d.get('holdId')")
hold_status=$(json "$REPLY_BODY" "d.get('status')")
[ "$hold_status" = "ACTIVE" ] \
  && pass "the hold is ACTIVE immediately" \
  || fail "unexpected hold status: $hold_status" "$REPLY_BODY"

# The projection travels case-hold -> Kafka -> ingestion -> outbox -> search,
# so this asserts the whole chain, not just that a row was written.
on_hold=""
for elapsed in $(seq 1 "$PROJECTION_TIMEOUT_SECONDS"); do
  request GET "/api/search?q=$RUN_ID&onHold=true&size=1"
  on_hold=$(json "$REPLY_BODY" "d.get('total')")
  if [ "${on_hold:-0}" -ge 1 ]; then
    pass "hold state reached the search index after ${elapsed}s"
    break
  fi
  sleep 1
done

[ "${on_hold:-0}" -ge 1 ] \
  || fail "hold state never reached the index within ${PROJECTION_TIMEOUT_SECONDS}s" "$REPLY_BODY"

request DELETE "/api/ingestion/messages/$MESSAGE_ID"
expect 409 "deletion of a held message is refused"

grep -q "under legal hold" <<<"$REPLY_BODY" \
  && pass "the refusal names the reason" \
  || fail "the refusal does not mention the hold" "$REPLY_BODY"

# ------------------------------------------------------------------ export

step "Export: build and verify an evidence package"

request POST "/api/exports" \
  "{\"caseId\":\"$CASE_ID\",\"scope\":\"CASE\",\"requestedBy\":\"smoke-test\"}"
expect 202 "export requested"
EXPORT_ID=$(json "$REPLY_BODY" "d.get('exportId')")

export_status=""
for elapsed in $(seq 1 "$EXPORT_TIMEOUT_SECONDS"); do
  request GET "/api/exports/$EXPORT_ID"
  export_status=$(json "$REPLY_BODY" "d.get('status')")
  case "$export_status" in
    COMPLETED) pass "package built after ${elapsed}s"; break ;;
    FAILED)    fail "export failed" "$REPLY_BODY" ;;
  esac
  sleep 1
done

[ "$export_status" = "COMPLETED" ] \
  || fail "export did not complete within ${EXPORT_TIMEOUT_SECONDS}s" "$REPLY_BODY"

request GET "/api/exports/$EXPORT_ID/manifest"
expect 200 "manifest readable"
manifest_items=$(json "$REPLY_BODY" "len(d.get('items') or [])")
[ "${manifest_items:-0}" -ge 1 ] \
  && pass "the manifest lists $manifest_items item(s) with checksums" \
  || fail "the manifest is empty" "$REPLY_BODY"

request POST "/api/exports/$EXPORT_ID/verify"
expect 200 "verification ran"
verified=$(json "$REPLY_BODY" "d.get('verified')")
[ "$verified" = "True" ] \
  && pass "every checksum matches the manifest" \
  || fail "verification failed" "$REPLY_BODY"

request GET "/api/exports/$EXPORT_ID/download"
expect 200 "presigned download URL issued"
grep -q "http" <<<"$REPLY_BODY" \
  && pass "the URL is populated" \
  || fail "no download URL in the response" "$REPLY_BODY"

# ------------------------------------------------------------------- audit

step "Audit: the chain of custody recorded every step"

# The audit trail is fed asynchronously from three services, so give the
# consumers a moment before asserting on it.
sleep 8

request GET "/api/audit/cases/$CASE_ID"
expect 200 "case audit trail readable"

for action in CASE_CREATED HOLD_CREATED EXPORT_REQUESTED EXPORT_COMPLETED; do
  present=$(json "$REPLY_BODY" "any(e.get('action') == '$action' for e in d)")
  [ "$present" = "True" ] \
    && pass "$action recorded" \
    || fail "$action missing from the case trail" "$REPLY_BODY"
done

# Published by ingestion, which is the service that would actually destroy the
# message — so this is the one that proves the refusal is auditable, not just
# logged.
request GET "/api/audit/search?action=DELETION_BLOCKED&targetId=$MESSAGE_ID"
blocked=$(json "$REPLY_BODY" "d.get('total')")
[ "${blocked:-0}" -ge 1 ] \
  && pass "DELETION_BLOCKED recorded against the message" \
  || fail "the blocked deletion was never audited" "$REPLY_BODY"

request GET "/api/audit/actors"
expect 200 "actor list available"

request GET "/api/audit/search?caseId=$CASE_ID&size=2"
page_size=$(json "$REPLY_BODY" "len(d.get('entries') or [])")
page_total=$(json "$REPLY_BODY" "d.get('total')")
[ "${page_size:-0}" -le 2 ] && [ "${page_total:-0}" -gt "${page_size:-0}" ] \
  && pass "paging returns $page_size of $page_total" \
  || dim "  note paging not exercised: only $page_total entr(y|ies) on this case"

# --------------------------------------------------------------- retention

step "Retention: policy is readable and the floor holds"

request GET "/api/ingestion/retention/policies"
expect 200 "retention policies readable"

request PUT "/api/ingestion/retention/policies/EMAIL" \
  '{"retentionMinutes":1,"updatedBy":"smoke-test"}'
if [ "$REPLY_CODE" = "400" ]; then
  pass "a one-minute period is refused below the floor"
elif [ "$REPLY_CODE" = "200" ]; then
  # Legitimate when the environment sets allow-short-retention, which is what
  # the retention demo needs.
  dim "  note short retention is permitted on this environment"
  PASSED=$((PASSED + 1))
else
  fail "unexpected response to a short retention period" "$REPLY_BODY"
fi

# --------------------------------------------------------------- integrity

step "Index integrity: the mapping is explicit, not inferred"

mapping=$(curl -sS "$SEARCH_DIRECT/../" -o /dev/null -w '' 2>/dev/null; \
  curl -sS "http://localhost:9200/messages/_mapping" 2>/dev/null)
if [ -n "$mapping" ]; then
  message_id_type=$(json "$mapping" \
    "list(d.values())[0]['mappings']['properties']['messageId']['type']")
  [ "$message_id_type" = "keyword" ] \
    && pass "messageId is mapped as keyword" \
    || fail "messageId is '$message_id_type', not keyword: the index was auto-created with a dynamic mapping, so exact-match filters will silently return nothing. Rebuild with: curl -X DELETE localhost:9200/messages && curl -X POST '$SEARCH_DIRECT/api/search/reindex?force=true'"
else
  dim "  note Elasticsearch not reachable on :9200, mapping check skipped"
fi

# ------------------------------------------------------------------ result

step "Result"
green "$PASSED checks passed"
dim "case=$CASE_ID hold=$HOLD_ID export=$EXPORT_ID message=$MESSAGE_ID"
