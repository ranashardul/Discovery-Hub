# DiscoveryHub UI

Frontend for DiscoveryHub, an e-discovery and legal-hold platform: search an
archive of business communications, scope them onto a case, freeze them under
legal hold, export them as verifiable evidence, and read the whole chain of
custody back.

Angular 22, standalone components, signals, SCSS. No UI framework dependency.

**This is a prototype front end.** It runs against an in-memory backend that
simulates the four microservices, so every screen is fully clickable with no
services running. Nothing is stubbed at the component level — the app talks to
real API contracts and the mock sits behind them.

## Run it

```bash
npm install
npm start          # http://localhost:4200
npm test           # unit tests (vitest)
npm run build      # production bundle
```

The corpus is generated in the browser on load from a fixed seed: ~10,000
messages across 24 custodians, two communication types, threaded, with
attachments on roughly 8% of items. The same seed always produces the same
message IDs, so IDs quoted during a demo keep resolving.

## Screens

| Route | Covers |
| --- | --- |
| `/dashboard` | System counts, archive composition, per-service health, demo path |
| `/search` | Full-text search, filters, highlighted hits, pagination, bulk evidence capture, saved searches |
| `/cases` | Case list with status filter, case creation |
| `/cases/:id` | Evidence, custodians, holds, saved searches, exports and chain of custody for one matter |
| `/holds` | Holds across all cases, propagation progress, release, deletion test |
| `/exports` | Export jobs with live status, manifest, checksum verification, retry, expiring links |
| `/retention` | Retention policy per communication type, manual disposition run, run history |
| `/audit` | Append-only audit trail with filters and before/after detail |
| `/messages/:id` | One message, its attachments, its hold state and its thread |

## Things worth clicking

- **Bulk capture** — search, then "Add all N to case". The full result set is
  resolved by the API rather than paged through in the browser.
- **Hold propagation** — placing a hold returns immediately with a
  `PROPAGATING` status and a progress bar that fills as the worker stamps the
  scope. A large scope never blocks the request.
- **Deletion is blocked** — Holds → "Load a held message" → "Attempt delete".
  The platform refuses and writes a `DELETION_BLOCKED` audit entry.
- **Retention vs holds** — set email retention to 5 minutes, run disposition,
  and watch it delete what it may while skipping everything under hold.
- **Tamper detection** — Exports → "Tamper", then "Verify". The recomputed
  checksums no longer match the manifest and verification fails loudly.
- **Closed cases are read-only** — close a case and every mutating control
  disables; the API refuses the call regardless.
- **Graceful degradation** — Dashboard → "Simulate outage" on any service.
  Screens that depend on it show a contained error band; the rest keep working.

## Structure

```
src/app/
  core/
    models/          domain types (message, case, hold, export, audit, retention)
    api/             abstract API contracts, used as DI tokens + providers
    mock/            in-memory backend: corpus generator, search engine, store
  shared/            design-system pieces: chips, modal, toasts, state blocks, pipes
  features/          one folder per screen, lazily loaded
```

The layering rule: **no component imports anything from `core/mock`** except
two explicitly demo-only affordances (outage simulation and package tampering),
both gated behind `environment.useMockBackend`.

## Connecting the real services

Components inject the abstract contracts in `src/app/core/api` — `SearchApi`,
`CaseApi`, `HoldApi`, `ExportApi`, `AuditApi`, `PlatformApi` — never a concrete
implementation. To go live:

1. Write one HTTP class per contract (e.g. `HttpSearchApi extends SearchApi`)
   using the base URLs in `src/environments/environment.ts` and
   `provideHttpClient()`.
2. List them in the non-mock branch of `provideDiscoveryHubApi()` in
   `src/app/core/api/providers.ts`.
3. Set `environment.useMockBackend = false`.

No component, template or model changes. Errors already flow as `ApiError`
with HTTP status codes, so the existing 400/404/409/503 handling still applies.

`proxy.conf.json` keeps the browser on a single origin during development, so
the Spring services do not need CORS configuration:

| Path | Service | Port |
| --- | --- | --- |
| `/api/ingestion` | Ingestion & archival | 8081 |
| `/api/search` | Search | 8082 |
| `/api/v1/cases`, `/api/v1/holds` | Case & hold | 8083 |
| `/api/v1/exports`, `/api/v1/audit` | Export & audit | 8084 |

### Contract notes

The search contract matches the implemented service exactly: `GET /api/search`
with `q` (required), `communicationType`, `sender`, `recipient`, `threadId`,
`dispositionStatus`, `onHold`, `hasAttachments`, `after`, `before`,
`sort=relevance|newest|oldest`, `from`, `size`, plus
`GET /api/search/messages/{id}` and `GET /api/search/stats`.

Two additions the UI needs from search, which the current service does not yet
expose: per-field `highlights` on each hit (FR-3.3 asks for highlighted terms)
and an endpoint that resolves a criteria set to all matching message IDs, so
"add all results to case" does not page through the archive client side.

The case, hold, export and audit contracts follow the service design documents.
Confirm the field names against the services as they land.
