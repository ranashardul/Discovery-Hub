/**
 * Hold lifecycle, matching `HoldStatus` in case-hold-service.
 *
 * A hold is ACTIVE or RELEASED the moment it is written to PostgreSQL.
 * Propagation onto the message data happens asynchronously afterwards, via
 * `case-hold.events` → ingestion → `holdIds`/`holdCount`, but the hold record
 * itself has no intermediate state. There is deliberately no PROPAGATING here:
 * the legal artefact exists as soon as it is recorded, and showing it as
 * pending would understate that.
 */
export type HoldStatus = 'ACTIVE' | 'RELEASED';

export const HOLD_STATUSES: readonly HoldStatus[] = ['ACTIVE', 'RELEASED'];

/** Narrows a status off the wire, failing loudly rather than casting blindly. */
export function toHoldStatus(value: string): HoldStatus {
  if ((HOLD_STATUSES as readonly string[]).includes(value)) {
    return value as HoldStatus;
  }
  throw new Error(`Unknown hold status from case-hold-service: ${value}`);
}

export interface HoldScope {
  custodianIds: string[];
  after: string | null;
  before: string | null;
  /** Optional narrowing terms; empty means the whole custodian/date scope. */
  searchTerms: string | null;
}

export interface LegalHold {
  id: string;
  caseId: string;
  caseName: string;
  reason: string;
  status: HoldStatus;
  scope: HoldScope;
  placedAt: string;
  placedBy: string;
  releasedAt: string | null;
  releasedBy: string | null;
  /** Communications the hold covers, as recorded by the case-hold service. */
  matchedMessageCount: number;
}

export interface PlaceHoldRequest {
  caseId: string;
  reason: string;
  scope: HoldScope;
}

/** Outcome of asking the platform to delete a message (FR-4.6). */
export interface DeletionAttemptResult {
  messageId: string;
  deleted: boolean;
  reason: string;
  blockingHoldIds: string[];
  attemptedAt: string;
}
